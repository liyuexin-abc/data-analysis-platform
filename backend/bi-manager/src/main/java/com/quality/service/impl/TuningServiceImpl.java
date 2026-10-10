package com.quality.service.impl;

import com.quality.util.Jsons;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bi.entity.OlapBasicPro;
import com.bi.entity.OlapTablePro;
import com.bi.mapper.OlapBasicProMapper;
import com.bi.mapper.OlapTableFieldMappingMapper;
import com.bi.mapper.OlapTableProMapper;
import com.bi.entity.OlapTableFieldMapping;
import com.common.base.UserThreadLocal;
import com.common.exception.BizException;
import com.common.models.SaasUser;
import com.common.result.PageResult;
import com.quality.client.DownstreamClient;
import com.quality.client.QualityProperties;
import com.quality.dto.*;
import com.quality.engine.AssetApplier;
import com.quality.engine.SuggestionEngine;
import com.quality.entity.*;
import com.quality.mapper.*;
import com.quality.service.RegressionService;
import com.quality.service.TuningService;
import com.quality.service.TuningVerifyRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TuningServiceImpl implements TuningService {

    public static final String DRAFT = "DRAFT", APPLIED = "APPLIED", VERIFYING = "VERIFYING", VERIFIED = "VERIFIED",
            PENDING_APPROVAL = "PENDING_APPROVAL", PUBLISHED = "PUBLISHED", ROLLED_BACK = "ROLLED_BACK", CANCELLED = "CANCELLED";

    private final TuningTaskMapper taskMapper;
    private final TuningChangeMapper changeMapper;
    private final AssetSnapshotMapper snapshotMapper;
    private final TuningVerifyReportMapper reportMapper;
    private final TuningVerifyCaseMapper caseMapper;
    private final TuningVerifyConfigMapper configMapper;
    private final TuningRuleMapper ruleMapper;
    private final TuningAuditLogMapper auditMapper;
    private final TuningNotificationMapper notificationMapper;
    private final ChatErrorDiagnosisMapper diagnosisMapper;
    private final ChatAnalysisTraceMapper traceMapper;
    private final ChatStepTraceMapper stepTraceMapper;
    private final ChatFeedbackMapper feedbackMapper;
    private final OlapTableProMapper tableMapper;
    private final OlapBasicProMapper basicMapper;
    private final OlapTableFieldMappingMapper fieldMappingMapper;
    private final AssetApplier applier;
    private final DownstreamClient downstream;
    private final RegressionService regressionService;
    private final TuningVerifyRunner verifyRunner;
    private final QualityProperties props;

    // ================================================================ suggest

    @Override
    public List<ChangeDTO> suggest(Long diagnosisId) {
        ChatErrorDiagnosis d = diagnosisMapper.selectById(diagnosisId);
        if (d == null) throw new BizException(404, "诊断不存在");
        ChatAnalysisTrace t = traceByChat(d.getChatId());
        List<ChatStepTrace> steps = stepTraceMapper.selectList(new LambdaQueryWrapper<ChatStepTrace>().eq(ChatStepTrace::getChatId, d.getChatId()));
        List<TuningRule> rules = ruleMapper.selectList(new LambdaQueryWrapper<TuningRule>().eq(TuningRule::getEnabled, 1).orderByAsc(TuningRule::getPriority));
        return SuggestionEngine.suggest(d, t, steps, rules, buildAssetContext(d, t, steps));
    }

    private SuggestionEngine.AssetContext buildAssetContext(ChatErrorDiagnosis d, ChatAnalysisTrace t, List<ChatStepTrace> steps) {
        SuggestionEngine.AssetContext ctx = new SuggestionEngine.AssetContext();
        Set<String> tableNames = new HashSet<>();
        if (StringUtils.isNotBlank(d.getExpectedTable())) tableNames.add(d.getExpectedTable());
        if (StringUtils.isNotBlank(d.getActualTable())) tableNames.add(d.getActualTable());
        if (t != null) tableNames.addAll(ChatQualityServiceImpl.parseArr(t.getUsedTables()));
        if (!tableNames.isEmpty()) {
            for (OlapTablePro tp : tableMapper.selectList(new LambdaQueryWrapper<OlapTablePro>().in(OlapTablePro::getTbName, tableNames))) {
                JSONObject o = new JSONObject();
                o.put("id", tp.getId());
                o.put("note", tp.getNote());
                ctx.tables.put(tp.getTbName(), o);
            }
        }
        Set<String> codes = new HashSet<>();
        try {
            for (Object e : JSON.parseArray(StringUtils.defaultIfBlank(d.getExpectedEntities(), "[]"))) {
                String c = ((JSONObject) e).getString("code");
                if (StringUtils.isNotBlank(c)) codes.add(c);
            }
        } catch (Exception ignore) {
        }
        for (ChatStepTrace s : steps) codes.addAll(ChatQualityServiceImpl.parseArr(s.getUnmatchedColumns()));
        // 全部实体名用于 R7 最近邻（限制 2000 条）
        List<OlapBasicPro> all = basicMapper.selectList(new LambdaQueryWrapper<OlapBasicPro>()
                .select(OlapBasicPro::getId, OlapBasicPro::getEnglishName, OlapBasicPro::getAlias, OlapBasicPro::getChineseName, OlapBasicPro::getCategory)
                .last("limit 2000"));
        for (OlapBasicPro b : all) {
            if (StringUtils.isBlank(b.getEnglishName())) continue;
            JSONObject o = new JSONObject();
            o.put("id", b.getId());
            o.put("alias", b.getAlias());
            o.put("chineseName", b.getChineseName());
            o.put("category", b.getCategory());
            ctx.entities.put(b.getEnglishName(), o);
            if (codes.contains(b.getEnglishName()) && b.getCategory() != null && b.getCategory() == 2) {
                OlapTableFieldMapping fm = fieldMappingMapper.selectOne(new LambdaQueryWrapper<OlapTableFieldMapping>()
                        .eq(OlapTableFieldMapping::getBasicId, b.getId()).last("limit 1"));
                if (fm != null) {
                    JSONObject c = new JSONObject();
                    c.put("tableId", fm.getTableId());
                    c.put("basicId", fm.getBasicId());
                    c.put("summary", fm.getSummary());
                    ctx.columnAgg.put(b.getEnglishName(), c);
                }
            }
        }
        if (StringUtils.isNotBlank(d.getAiBodyCode())) {
            ctx.agentTables = applier.agentTableNames(d.getAiBodyCode());
            var body = applier.aiBodyByCode(d.getAiBodyCode(), null);
            ctx.aiBodyId = body == null ? null : String.valueOf(body.getId());
        }
        try {
            JSONObject main = downstream.promptCurrent("prompt-main");
            ctx.promptMainVersion = main == null ? "v?" : main.getString("version");
            JSONObject sum = downstream.promptCurrent("prompt-summary");
            ctx.promptSummaryVersion = sum == null ? "v?" : sum.getString("version");
        } catch (Exception e) {
            ctx.promptMainVersion = t == null || StringUtils.isBlank(t.getPromptVersion()) ? "v?" : t.getPromptVersion();
            ctx.promptSummaryVersion = "v?";
        }
        return ctx;
    }

    // ================================================================= task

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TuningTask createTask(TaskCreateDTO dto) {
        ChatErrorDiagnosis d = diagnosisMapper.selectById(dto.getDiagnosisId());
        if (d == null) throw new BizException(404, "诊断不存在");
        ChatAnalysisTrace t = traceByChat(d.getChatId());
        SaasUser u = UserThreadLocal.get();
        TuningTask task = new TuningTask();
        task.setTaskNo(nextTaskNo());
        task.setDiagnosisId(d.getId());
        task.setChatId(d.getChatId());
        task.setAiBodyCode(d.getAiBodyCode());
        task.setTenantId(d.getTenantId());
        task.setPrimaryErrorType(d.getPrimaryErrorType());
        task.setQuestion(t == null ? null : t.getQuestion());
        task.setStatus(DRAFT);
        task.setRound(1);
        task.setVerifyConfig(dto.getVerifyConfig() == null ? null : JSON.toJSONString(dto.getVerifyConfig()));
        task.setCreatedBy(u == null ? "system" : u.getUsername());
        taskMapper.insert(task);
        List<ChangeDTO> changes = dto.getChanges() == null || dto.getChanges().isEmpty() ? suggest(d.getId()) : dto.getChanges();
        saveChanges(task.getId(), changes);
        audit(task, "CREATE", null, DRAFT, changes.size() + " 条变更");
        return task;
    }

    private void saveChanges(Long taskId, List<ChangeDTO> changes) {
        changeMapper.delete(new LambdaQueryWrapper<TuningChange>().eq(TuningChange::getTaskId, taskId));
        int seq = 1;
        for (ChangeDTO c : changes) {
            TuningChange e = new TuningChange();
            e.setTaskId(taskId);
            e.setSeq(seq++);
            e.setSource(StringUtils.defaultIfBlank(c.getSource(), "MANUAL"));
            e.setRuleCode(c.getRuleCode());
            e.setConfidence(StringUtils.defaultIfBlank(c.getConfidence(), "HIGH"));
            e.setAccepted(Boolean.FALSE.equals(c.getAccepted()) ? 0 : 1);
            e.setAssetType(c.getAssetType());
            e.setTargetModule(StringUtils.defaultIfBlank(c.getTargetModule(), moduleOf(c.getAssetType())));
            e.setTargetId(c.getTargetId());
            e.setTargetLabel(c.getTargetLabel());
            e.setField(c.getField());
            e.setBeforeValue(c.getBeforeValue());
            e.setAfterValue(c.getAfterValue());
            e.setDiffSummary(c.getDiffSummary());
            e.setReason(c.getReason());
            e.setApplyStatus("PENDING");
            changeMapper.insert(e);
        }
    }

    static String moduleOf(String assetType) {
        if (assetType == null) return "BI_MANAGER";
        if (assetType.startsWith("PROMPT") || assetType.startsWith("SYSTEM_B")) return "SYSTEM_B";
        if (assetType.startsWith("RECALL")) return "RECALL";
        return "BI_MANAGER";
    }

    private String nextTaskNo() {
        String day = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long cnt = taskMapper.selectCount(new LambdaQueryWrapper<TuningTask>().likeRight(TuningTask::getTaskNo, "TN" + day));
        return String.format("TN%s-%03d", day, cnt + 1);
    }

    @Override
    public PageResult<JSONObject> taskPage(PageQuery q) {
        LambdaQueryWrapper<TuningTask> w = new LambdaQueryWrapper<TuningTask>()
                .eq(StringUtils.isNotBlank(q.getAiBodyCode()), TuningTask::getAiBodyCode, q.getAiBodyCode())
                .eq(StringUtils.isNotBlank(q.getErrorType()), TuningTask::getPrimaryErrorType, q.getErrorType())
                .and(StringUtils.isNotBlank(q.getKeyword()), x -> x.like(TuningTask::getTaskNo, q.getKeyword()).or().like(TuningTask::getQuestion, q.getKeyword()))
                .orderByDesc(TuningTask::getCreatedAt);
        if (StringUtils.isNotBlank(q.getStatusStr())) {
            if ("pending".equals(q.getStatusStr())) w.in(TuningTask::getStatus, VERIFIED, PENDING_APPROVAL);
            else w.in(TuningTask::getStatus, Arrays.asList(q.getStatusStr().split(",")));
        }
        Page<TuningTask> page = taskMapper.selectPage(new Page<>(q.getPage(), q.getPageSize()), w);
        List<JSONObject> rows = page.getRecords().stream().map(this::taskJson).collect(Collectors.toList());
        return new PageResult<>(page.getTotal(), rows, page.getCurrent(), page.getSize());
    }

    private JSONObject taskJson(TuningTask t) {
        JSONObject o = Jsons.obj(t);
        o.put("verifySummary", ChatQualityServiceImpl.parseJson(t.getVerifySummary()));
        o.put("verifyConfig", ChatQualityServiceImpl.parseJson(t.getVerifyConfig()));
        o.put("onlineRecheck", ChatQualityServiceImpl.parseJson(t.getOnlineRecheck()));
        o.put("observeAlert", ChatQualityServiceImpl.parseJson(t.getObserveAlert()));
        o.put("changeCount", changeMapper.selectCount(new LambdaQueryWrapper<TuningChange>().eq(TuningChange::getTaskId, t.getId()).eq(TuningChange::getAccepted, 1)));
        return o;
    }

    @Override
    public JSONObject taskDetail(Long id) {
        TuningTask t = mustTask(id);
        JSONObject o = taskJson(t);
        o.put("changes", changeMapper.selectList(new LambdaQueryWrapper<TuningChange>().eq(TuningChange::getTaskId, id).orderByAsc(TuningChange::getSeq)));
        o.put("snapshots", snapshotMapper.selectList(new LambdaQueryWrapper<AssetSnapshot>().eq(AssetSnapshot::getTaskId, id)));
        o.put("audit", auditMapper.selectList(new LambdaQueryWrapper<TuningAuditLog>().eq(TuningAuditLog::getTaskId, id).orderByAsc(TuningAuditLog::getCreatedAt)));
        ChatErrorDiagnosis d = t.getDiagnosisId() == null ? null : diagnosisMapper.selectById(t.getDiagnosisId());
        o.put("diagnosis", d);
        if (t.getVerifyReportId() != null) {
            TuningVerifyReport r = reportMapper.selectById(t.getVerifyReportId());
            o.put("report", r);
        }
        o.put("effectiveConfig", effectiveConfig(t));
        return o;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TuningTask updateChanges(Long id, List<ChangeDTO> changes, Map<String, Object> verifyConfig) {
        TuningTask t = mustTask(id);
        if (!DRAFT.equals(t.getStatus()) && !VERIFIED.equals(t.getStatus())) {
            throw new BizException(400, "仅 DRAFT / VERIFIED 状态可编辑变更，当前 " + t.getStatus());
        }
        String before = t.getStatus();
        if (changes != null) saveChanges(id, changes);
        if (verifyConfig != null) t.setVerifyConfig(JSON.toJSONString(verifyConfig));
        if (VERIFIED.equals(t.getStatus())) {
            // 已验证后再改 → round+1 回 DRAFT，并回滚已 APPLIED 的草稿（Phase 1 草稿不落库，无需回滚）
            t.setRound(t.getRound() + 1);
            t.setStatus(DRAFT);
            t.setVerifySummary(null);
        }
        taskMapper.updateById(t);
        audit(t, "UPDATE_CHANGES", before, t.getStatus(), null);
        return t;
    }

    // ============================================================== execute

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TuningTask execute(Long id) {
        TuningTask t = mustTask(id);
        if (!DRAFT.equals(t.getStatus())) throw new BizException(400, "仅 DRAFT 可执行，当前 " + t.getStatus());
        List<TuningChange> changes = acceptedChanges(id);
        if (changes.isEmpty()) throw new BizException(400, "没有已采纳的变更");
        // 并发保护：同一资产被其他进行中任务持有
        for (TuningChange c : changes) {
            List<TuningChange> others = changeMapper.selectList(new LambdaQueryWrapper<TuningChange>()
                    .eq(TuningChange::getAssetType, c.getAssetType()).eq(TuningChange::getTargetId, c.getTargetId())
                    .eq(TuningChange::getField, c.getField()).ne(TuningChange::getTaskId, id).eq(TuningChange::getAccepted, 1));
            for (TuningChange o : others) {
                TuningTask ot = taskMapper.selectById(o.getTaskId());
                if (ot != null && Set.of(APPLIED, VERIFYING, VERIFIED, PENDING_APPROVAL).contains(ot.getStatus())) {
                    throw new BizException(409, "资产「" + c.getTargetLabel() + "." + c.getField() + "」正在被任务 " + ot.getTaskNo() + " 调优中");
                }
            }
        }
        // 快照（执行那一刻的线上值）
        snapshotMapper.delete(new LambdaQueryWrapper<AssetSnapshot>().eq(AssetSnapshot::getTaskId, id));
        for (TuningChange c : changes) {
            JSONObject snap = applier.snapshot(c);
            AssetSnapshot s = new AssetSnapshot();
            s.setTaskId(id);
            s.setChangeId(c.getId());
            s.setAssetType(c.getAssetType());
            s.setTargetModule(c.getTargetModule());
            s.setTargetId(c.getTargetId());
            s.setField(c.getField());
            s.setSnapshotValue(snap.getString("value"));
            s.setSnapshotVersion(snap.getString("version"));
            snapshotMapper.insert(s);
            if (StringUtils.isBlank(c.getBeforeValue()) && snap.getString("value") != null) {
                c.setBeforeValue(snap.getString("value"));
            }
            c.setApplyStatus("APPLIED");
            c.setAppliedAt(LocalDateTime.now());
            changeMapper.updateById(c);
        }
        t.setStatus(APPLIED);
        taskMapper.updateById(t);
        audit(t, "EXECUTE", DRAFT, APPLIED, "快照 " + changes.size() + " 条，草稿已应用（内存覆盖）");
        return startVerify(t, Collections.emptyList());
    }

    @Override
    public TuningTask verify(Long id, List<String> extraQuestions) {
        TuningTask t = mustTask(id);
        if (!Set.of(APPLIED, VERIFIED, VERIFYING).contains(t.getStatus())) {
            throw new BizException(400, "当前状态不可验证: " + t.getStatus());
        }
        if (VERIFYING.equals(t.getStatus())) verifyRunner.abort(id);
        return startVerify(t, extraQuestions == null ? Collections.emptyList() : extraQuestions);
    }

    private TuningTask startVerify(TuningTask t, List<String> extraQuestions) {
        String before = t.getStatus();
        TuningVerifyConfig cfg = effectiveConfig(t);
        // 组装验证集
        List<TuningVerifyCase> cases = assembleCases(t, cfg, extraQuestions);
        TuningVerifyReport r = new TuningVerifyReport();
        r.setTaskId(t.getId());
        r.setRound(t.getRound());
        r.setStatus("RUNNING");
        r.setCaseTotal(cases.size());
        r.setCaseDone(0);
        reportMapper.insert(r);
        for (TuningVerifyCase c : cases) {
            c.setReportId(r.getId());
            caseMapper.insert(c);
        }
        t.setVerifyReportId(r.getId());
        t.setStatus(VERIFYING);
        taskMapper.updateById(t);
        audit(t, "VERIFY_START", before, VERIFYING, "验证集 " + cases.size() + " 题");
        // 草稿 meta
        List<TuningChange> changes = acceptedChanges(t.getId());
        JSONObject base;
        try {
            base = downstream.agentMeta(t.getAiBodyCode());
        } catch (Exception e) {
            log.warn("[quality] agentMeta failed, fallback empty meta: {}", e.getMessage());
            base = new JSONObject();
        }
        JSONObject draft = applier.buildDraftMeta(base, changes, t.getAiBodyCode());
        final Long taskId = t.getId(), reportId = r.getId();
        final JSONObject meta = draft.getJSONObject("meta");
        final Map<String, String> overrides = toStrMap(draft.getJSONObject("prompt_overrides"));
        final com.common.models.SaasUser user = UserThreadLocal.get();
        // 必须在当前事务提交后再启动异步验证：否则 mock/快速下游可能在事务提交前就把任务写成 VERIFIED，
        // 随后本事务提交会用过期的 VERIFYING 覆盖它（任务卡死在 VERIFYING）。
        Runnable start = () -> verifyRunner.run(taskId, reportId, meta, overrides, cfg, user);
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { start.run(); }
                    });
        } else {
            start.run();
        }
        return t;
    }

    private static Map<String, String> toStrMap(JSONObject o) {
        Map<String, String> m = new LinkedHashMap<>();
        if (o != null) for (String k : o.keySet()) m.put(k, o.getString(k));
        return m;
    }

    /** §6.2 验证集 = ORIGIN(1) + SIMILAR(≤N) + REGRESSION(≤M) + MANUAL，去重，总上限 max_cases */
    List<TuningVerifyCase> assembleCases(TuningTask t, TuningVerifyConfig cfg, List<String> extraQuestions) {
        List<TuningVerifyCase> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        ChatErrorDiagnosis d = t.getDiagnosisId() == null ? null : diagnosisMapper.selectById(t.getDiagnosisId());
        ChatAnalysisTrace origin = traceByChat(t.getChatId());
        if (origin != null && d != null) {
            TuningVerifyCase c = caseOf("ORIGIN", origin, RegressionServiceImpl.expectedFromDiagnosis(origin, d));
            c.setBeforePass(0); // 原问题 before 必为失败
            out.add(c);
            seen.add(norm(origin.getQuestion()));
        }
        // SIMILAR：同智能体、同主错误类型（有诊断）或同表（trace），优先有 👎
        if (cfg.getSimilarLimit() > 0) {
            List<String> downIds = feedbackMapper.selectList(new LambdaQueryWrapper<ChatFeedback>()
                    .eq(ChatFeedback::getRating, -1).eq(StringUtils.isNotBlank(t.getAiBodyCode()), ChatFeedback::getAiBodyCode, t.getAiBodyCode())
                    .select(ChatFeedback::getChatId).last("limit 200")).stream().map(ChatFeedback::getChatId).collect(Collectors.toList());
            List<ChatErrorDiagnosis> sameType = diagnosisMapper.selectList(new LambdaQueryWrapper<ChatErrorDiagnosis>()
                    .eq(ChatErrorDiagnosis::getPrimaryErrorType, t.getPrimaryErrorType())
                    .eq(StringUtils.isNotBlank(t.getAiBodyCode()), ChatErrorDiagnosis::getAiBodyCode, t.getAiBodyCode())
                    .ne(ChatErrorDiagnosis::getChatId, t.getChatId()).orderByDesc(ChatErrorDiagnosis::getDiagnosedAt).last("limit 100"));
            sameType.sort(Comparator.comparing((ChatErrorDiagnosis x) -> downIds.contains(x.getChatId()) ? 0 : 1));
            int n = 0;
            for (ChatErrorDiagnosis sd : sameType) {
                if (n >= cfg.getSimilarLimit()) break;
                ChatAnalysisTrace st = traceByChat(sd.getChatId());
                if (st == null || !seen.add(norm(st.getQuestion()))) continue;
                TuningVerifyCase c = caseOf("SIMILAR", st, RegressionServiceImpl.expectedFromDiagnosis(st, sd));
                c.setBeforePass(sd.getFixStatus() != null && sd.getFixStatus() >= 1 ? 1 : 0);
                out.add(c);
                n++;
            }
            if (n < cfg.getSimilarLimit() && origin != null) {
                List<String> tables = ChatQualityServiceImpl.parseArr(origin.getUsedTables());
                if (!tables.isEmpty()) {
                    List<ChatAnalysisTrace> sameTable = traceMapper.selectList(new LambdaQueryWrapper<ChatAnalysisTrace>()
                            .eq(StringUtils.isNotBlank(t.getAiBodyCode()), ChatAnalysisTrace::getAiBodyCode, t.getAiBodyCode())
                            .like(ChatAnalysisTrace::getUsedTables, tables.get(0)).ne(ChatAnalysisTrace::getChatId, t.getChatId())
                            .orderByDesc(ChatAnalysisTrace::getCreatedAt).last("limit 50"));
                    for (ChatAnalysisTrace st : sameTable) {
                        if (n >= cfg.getSimilarLimit()) break;
                        if (!seen.add(norm(st.getQuestion()))) continue;
                        TuningVerifyCase c = caseOf("SIMILAR", st, null); // 无诊断：只做变化检测
                        c.setBeforePass(null);
                        out.add(c);
                        n++;
                    }
                }
            }
        }
        // REGRESSION：tags 命中本次变更资产
        if (cfg.getRegressionLimit() > 0) {
            List<String> tags = acceptedChanges(t.getId()).stream().map(c -> AssetApplierCode.codeOf(c.getTargetLabel())).filter(StringUtils::isNotBlank).collect(Collectors.toList());
            for (RegressionCase rc : regressionService.pick(t.getAiBodyCode(), tags, cfg.getRegressionLimit())) {
                if (!seen.add(norm(rc.getQuestion()))) continue;
                TuningVerifyCase c = new TuningVerifyCase();
                c.setCaseType("REGRESSION");
                c.setRegressionCaseId(rc.getId());
                c.setSourceChatId(rc.getSourceChatId());
                c.setQuestion(rc.getQuestion());
                c.setExpected(rc.getExpected());
                c.setBeforePass(1); // 回归集默认为线上正确
                c.setStatus("PENDING");
                out.add(c);
            }
        }
        for (String q : extraQuestions) {
            if (StringUtils.isBlank(q) || !seen.add(norm(q))) continue;
            TuningVerifyCase c = new TuningVerifyCase();
            c.setCaseType("MANUAL");
            c.setQuestion(q.trim());
            c.setBeforePass(null);
            c.setStatus("PENDING");
            out.add(c);
        }
        if (out.size() > cfg.getMaxCases()) {
            // 保留 ORIGIN + MANUAL，其余按顺序裁剪
            List<TuningVerifyCase> keep = out.stream().filter(c -> "ORIGIN".equals(c.getCaseType()) || "MANUAL".equals(c.getCaseType())).collect(Collectors.toList());
            for (TuningVerifyCase c : out) {
                if (keep.size() >= cfg.getMaxCases()) break;
                if (!keep.contains(c)) keep.add(c);
            }
            out = keep;
        }
        return out;
    }

    private TuningVerifyCase caseOf(String type, ChatAnalysisTrace t, JSONObject expected) {
        TuningVerifyCase c = new TuningVerifyCase();
        c.setCaseType(type);
        c.setSourceChatId(t.getChatId());
        c.setQuestion(t.getQuestion());
        c.setExpected(expected == null ? null : expected.toJSONString());
        // before 复用历史 trace
        JSONObject before = new JSONObject();
        before.put("status", t.getStatus());
        before.put("used_tables", ChatQualityServiceImpl.parseJson(t.getUsedTables()));
        before.put("resolved_metrics", ChatQualityServiceImpl.parseJson(t.getResolvedMetrics()));
        before.put("resolved_dims", ChatQualityServiceImpl.parseJson(t.getResolvedDims()));
        before.put("answer", ChatQualityServiceImpl.abbreviate(t.getFinalAnswer(), 2000));
        before.put("step_count", ChatQualityServiceImpl.parseJsonArr(t.getDecompositionSteps()).size());
        c.setBeforeResult(before.toJSONString());
        c.setElapsedBeforeMs(t.getTotalElapsedMs());
        c.setStatus("PENDING");
        return c;
    }

    private static String norm(String q) {
        return StringUtils.defaultString(q).replaceAll("\\s+", "").toLowerCase();
    }

    @Override
    public void abortVerify(Long id) {
        TuningTask t = mustTask(id);
        verifyRunner.abort(id);
        if (t.getVerifyReportId() != null) {
            TuningVerifyReport r = reportMapper.selectById(t.getVerifyReportId());
            if (r != null && "RUNNING".equals(r.getStatus())) {
                r.setStatus("ABORTED");
                r.setFinishedAt(LocalDateTime.now());
                reportMapper.updateById(r);
            }
        }
        String before = t.getStatus();
        t.setStatus(APPLIED);
        taskMapper.updateById(t);
        audit(t, "VERIFY_ABORT", before, APPLIED, null);
    }

    @Override
    public JSONObject report(Long id) {
        TuningTask t = mustTask(id);
        JSONObject o = new JSONObject();
        if (t.getVerifyReportId() == null) {
            o.put("report", null);
            o.put("cases", Collections.emptyList());
            return o;
        }
        TuningVerifyReport r = reportMapper.selectById(t.getVerifyReportId());
        o.put("report", r);
        List<TuningVerifyCase> cases = caseMapper.selectList(new LambdaQueryWrapper<TuningVerifyCase>().eq(TuningVerifyCase::getReportId, t.getVerifyReportId()).orderByAsc(TuningVerifyCase::getId));
        o.put("cases", cases.stream().map(this::caseJson).collect(Collectors.toList()));
        o.put("status", t.getStatus());
        return o;
    }

    private JSONObject caseJson(TuningVerifyCase c) {
        JSONObject o = Jsons.obj(c);
        for (String k : Arrays.asList("expected", "beforeResult", "afterResult", "judgeDetail")) o.put(k, ChatQualityServiceImpl.parseJson(o.getString(k)));
        return o;
    }

    @Override
    public JSONObject verifyCase(Long caseId) {
        TuningVerifyCase c = caseMapper.selectById(caseId);
        if (c == null) throw new BizException(404, "用例不存在");
        return caseJson(c);
    }

    // ============================================================= approval

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TuningTask submitApproval(Long id, String confirmNote) {
        TuningTask t = mustTask(id);
        if (!VERIFIED.equals(t.getStatus())) throw new BizException(400, "仅 VERIFIED 可提交审批，当前 " + t.getStatus());
        TuningVerifyReport r = t.getVerifyReportId() == null ? null : reportMapper.selectById(t.getVerifyReportId());
        if (r == null || "FAIL".equals(r.getConclusion())) throw new BizException(400, "验证结论为 FAIL，不能提交审批");
        TuningVerifyConfig cfg = effectiveConfig(t);
        if ("PASS_WITH_WARN".equals(r.getConclusion()) && cfg.getWarnNeedNote() == 1 && StringUtils.isBlank(confirmNote)) {
            throw new BizException(400, "验证存在退化（PASS_WITH_WARN），请填写确认说明");
        }
        SaasUser u = UserThreadLocal.get();
        t.setConfirmNote(confirmNote);
        t.setSubmittedAt(LocalDateTime.now());
        t.setSubmittedBy(u == null ? "system" : u.getUsername());
        if (cfg.getRequireApproval() == 0) {
            // 配置关闭审批 → 直接发布
            t.setStatus(VERIFIED);
            taskMapper.updateById(t);
            return doPublish(t, t.getSubmittedBy(), "（配置：无需审批）");
        }
        t.setStatus(PENDING_APPROVAL);
        t.setApprovalId(t.getId()); // Phase 1 审批单内置于 bi-manager，id 复用任务 id
        taskMapper.updateById(t);
        audit(t, "SUBMIT_APPROVAL", VERIFIED, PENDING_APPROVAL, confirmNote);
        notify("APPROVAL_PENDING", t, "待审批：" + t.getTaskNo(), "任务 " + t.getTaskNo() + "（" + abbreviate(t.getQuestion(), 40) + "）等待超级管理员审批发布");
        return t;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TuningTask withdraw(Long id) {
        TuningTask t = mustTask(id);
        if (!PENDING_APPROVAL.equals(t.getStatus())) throw new BizException(400, "仅待审批可撤回");
        t.setStatus(VERIFIED);
        t.setSubmittedAt(null);
        taskMapper.updateById(t);
        audit(t, "WITHDRAW", PENDING_APPROVAL, VERIFIED, null);
        return t;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TuningTask approve(Long id, ApproveDTO dto) {
        TuningTask t = mustTask(id);
        if (!PENDING_APPROVAL.equals(t.getStatus())) throw new BizException(400, "仅待审批可审批，当前 " + t.getStatus());
        SaasUser u = UserThreadLocal.get();
        String who = u == null ? "super_admin" : u.getUsername();
        t.setApprovedAt(LocalDateTime.now());
        t.setApprovedBy(who);
        t.setApprovalComment(dto.getComment());
        if (!Boolean.TRUE.equals(dto.getApproved())) {
            t.setStatus(VERIFIED);
            taskMapper.updateById(t);
            audit(t, "REJECT", PENDING_APPROVAL, VERIFIED, dto.getComment());
            notify("APPROVAL_REJECTED", t, "审批驳回：" + t.getTaskNo(), StringUtils.defaultString(dto.getComment()), "QUALITY_ADMIN");
            return t;
        }
        taskMapper.updateById(t);
        audit(t, "APPROVE", PENDING_APPROVAL, PENDING_APPROVAL, dto.getComment());
        return doPublish(t, who, dto.getComment());
    }

    @Override
    public List<JSONObject> pendingApprovals() {
        return taskMapper.selectList(new LambdaQueryWrapper<TuningTask>().eq(TuningTask::getStatus, PENDING_APPROVAL).orderByAsc(TuningTask::getSubmittedAt))
                .stream().map(this::taskJson).collect(Collectors.toList());
    }

    // ============================================================== publish

    /** §6.5 发布：逐条写库 → 下游同步 → 诊断/反馈已修复 → 沉淀回归集 → 线上复验 → 观察期 */
    private TuningTask doPublish(TuningTask t, String operator, String comment) {
        String before = t.getStatus();
        List<TuningChange> changes = acceptedChanges(t.getId());
        List<String> errors = new ArrayList<>();
        boolean needRecallSync = false;
        for (TuningChange c : changes) {
            String err = applier.publish(c, t.getAiBodyCode());
            if (err == null) {
                c.setApplyStatus("PUBLISHED");
                c.setPublishedAt(LocalDateTime.now());
                if (!c.getAssetType().startsWith("PROMPT")) needRecallSync = true;
            } else {
                c.setApplyStatus("FAILED");
                c.setApplyError(abbreviate(err, 500));
                errors.add(c.getSeq() + ":" + err);
            }
            changeMapper.updateById(c);
        }
        if (needRecallSync) downstream.recallMetaSync();
        t.setStatus(PUBLISHED);
        t.setPublishedAt(LocalDateTime.now());
        t.setPublishedBy(operator);
        TuningVerifyConfig cfg = effectiveConfig(t);
        t.setObserveUntil(LocalDateTime.now().plusDays(cfg.getObserveDays()));
        taskMapper.updateById(t);
        audit(t, "PUBLISH", before, PUBLISHED, errors.isEmpty() ? "全部写入成功" + (comment == null ? "" : " | " + comment) : "部分失败: " + String.join("; ", errors));
        // 诊断 / 反馈 → 已修复
        if (t.getDiagnosisId() != null) {
            ChatErrorDiagnosis d = diagnosisMapper.selectById(t.getDiagnosisId());
            if (d != null) {
                d.setFixStatus(1);
                diagnosisMapper.updateById(d);
                for (ChatFeedback fb : feedbackMapper.selectList(new LambdaQueryWrapper<ChatFeedback>().eq(ChatFeedback::getChatId, d.getChatId()))) {
                    fb.setStatus(2);
                    feedbackMapper.updateById(fb);
                }
                // 沉淀回归集：原问题 + 本次通过的 SIMILAR
                ChatAnalysisTrace origin = traceByChat(d.getChatId());
                if (origin != null) regressionService.addFromDiagnosis(origin, d);
                if (t.getVerifyReportId() != null) {
                    for (TuningVerifyCase vc : caseMapper.selectList(new LambdaQueryWrapper<TuningVerifyCase>()
                            .eq(TuningVerifyCase::getReportId, t.getVerifyReportId()).eq(TuningVerifyCase::getCaseType, "SIMILAR")
                            .in(TuningVerifyCase::getVerdict, "FIXED", "IMPROVED", "PASS"))) {
                        ChatAnalysisTrace st = vc.getSourceChatId() == null ? null : traceByChat(vc.getSourceChatId());
                        if (st != null) regressionService.addFromTrace(st, "DIAGNOSIS");
                    }
                }
            }
        }
        // 线上复验（走完整链路含召回）
        if (cfg.getOnlineRecheck() == 1 && StringUtils.isNotBlank(t.getQuestion())) {
            verifyRunner.onlineRecheck(t.getId(), t.getQuestion(), t.getAiBodyCode(), UserThreadLocal.get());
        }
        if (!errors.isEmpty()) {
            notify("PUBLISH_PARTIAL_FAIL", t, "发布部分失败：" + t.getTaskNo(), String.join("; ", errors));
        }
        return t;
    }

    // ============================================================= rollback

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TuningTask rollback(Long id, RollbackDTO dto) {
        TuningTask t = mustTask(id);
        if (!Set.of(VERIFIED, PENDING_APPROVAL, PUBLISHED, APPLIED).contains(t.getStatus())) {
            throw new BizException(400, "当前状态不可回退: " + t.getStatus());
        }
        boolean wasPublished = PUBLISHED.equals(t.getStatus());
        if (wasPublished && StringUtils.isBlank(dto.getReason())) throw new BizException(400, "回退已发布任务必须填写原因");
        String before = t.getStatus();
        List<String> errors = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        if (wasPublished) {
            Map<Long, AssetSnapshot> snaps = snapshotMapper.selectList(new LambdaQueryWrapper<AssetSnapshot>().eq(AssetSnapshot::getTaskId, id))
                    .stream().filter(s -> s.getChangeId() != null).collect(Collectors.toMap(AssetSnapshot::getChangeId, s -> s, (a, b) -> a));
            for (TuningChange c : acceptedChanges(id)) {
                if (!"PUBLISHED".equals(c.getApplyStatus())) continue;
                // 冲突保护：同资产被更晚发布的任务改过
                List<TuningChange> later = changeMapper.selectList(new LambdaQueryWrapper<TuningChange>()
                        .eq(TuningChange::getAssetType, c.getAssetType()).eq(TuningChange::getTargetId, c.getTargetId()).eq(TuningChange::getField, c.getField())
                        .eq(TuningChange::getApplyStatus, "PUBLISHED").ne(TuningChange::getTaskId, id).gt(TuningChange::getPublishedAt, c.getPublishedAt()));
                if (!later.isEmpty()) {
                    TuningTask lt = taskMapper.selectById(later.get(0).getTaskId());
                    conflicts.add(c.getTargetLabel() + "." + c.getField() + " 已被 " + (lt == null ? "?" : lt.getTaskNo()) + " 再次修改");
                    if (!"FORCE".equalsIgnoreCase(dto.getMode())) continue;
                }
                AssetSnapshot s = snaps.get(c.getId());
                String err = applier.rollback(c, s == null ? c.getBeforeValue() : s.getSnapshotValue(), s == null ? null : s.getSnapshotVersion(), t.getAiBodyCode());
                if (err == null) {
                    c.setApplyStatus("ROLLED_BACK");
                    c.setRolledBackAt(LocalDateTime.now());
                } else {
                    c.setApplyError(abbreviate(err, 500));
                    errors.add(c.getSeq() + ":" + err);
                }
                changeMapper.updateById(c);
            }
            downstream.recallMetaSync();
            if (t.getDiagnosisId() != null) {
                ChatErrorDiagnosis d = diagnosisMapper.selectById(t.getDiagnosisId());
                if (d != null) {
                    d.setFixStatus(0);
                    diagnosisMapper.updateById(d);
                }
            }
        } else {
            for (TuningChange c : acceptedChanges(id)) {
                c.setApplyStatus("ROLLED_BACK");
                c.setRolledBackAt(LocalDateTime.now());
                changeMapper.updateById(c);
            }
        }
        SaasUser u = UserThreadLocal.get();
        t.setStatus(ROLLED_BACK);
        t.setRolledBackAt(LocalDateTime.now());
        t.setRolledBackBy(u == null ? "system" : u.getUsername());
        t.setRollbackReason(dto.getReason());
        taskMapper.updateById(t);
        String detail = (conflicts.isEmpty() ? "" : "冲突: " + String.join("; ", conflicts) + " | ") + (errors.isEmpty() ? "成功" : "失败: " + String.join("; ", errors));
        audit(t, "ROLLBACK", before, ROLLED_BACK, detail + (dto.getReason() == null ? "" : " | 原因: " + dto.getReason()));
        if (wasPublished) {
            notify("ROLLBACK_PUBLISHED", t, "已发布任务被回退：" + t.getTaskNo(), "原因：" + dto.getReason() + (conflicts.isEmpty() ? "" : "；" + String.join("; ", conflicts)));
        }
        return t;
    }

    @Override
    public TuningTask cancel(Long id) {
        TuningTask t = mustTask(id);
        if (!Set.of(DRAFT, APPLIED, VERIFIED).contains(t.getStatus())) throw new BizException(400, "当前状态不可取消");
        String before = t.getStatus();
        t.setStatus(CANCELLED);
        taskMapper.updateById(t);
        audit(t, "CANCEL", before, CANCELLED, null);
        return t;
    }

    @Override
    public JSONObject impact(Long id) {
        TuningTask t = mustTask(id);
        JSONObject o = new JSONObject();
        List<TuningChange> changes = acceptedChanges(id);
        Set<String> assets = changes.stream().map(c -> AssetApplierCode.codeOf(c.getTargetLabel())).filter(StringUtils::isNotBlank).collect(Collectors.toSet());
        LocalDateTime since = LocalDateTime.now().minusDays(30);
        long related = 0;
        for (String a : assets) {
            related += traceMapper.selectCount(new LambdaQueryWrapper<ChatAnalysisTrace>().ge(ChatAnalysisTrace::getCreatedAt, since)
                    .and(x -> x.like(ChatAnalysisTrace::getUsedTables, a).or().like(ChatAnalysisTrace::getResolvedMetrics, a).or().like(ChatAnalysisTrace::getResolvedDims, a)));
        }
        o.put("assets", assets);
        o.put("agents", t.getAiBodyCode() == null ? List.of() : List.of(t.getAiBodyCode()));
        o.put("relatedChats30d", related);
        o.put("promptChange", changes.stream().anyMatch(c -> c.getAssetType().startsWith("PROMPT")));
        return o;
    }

    // =============================================================== config

    @Override
    public TuningVerifyConfig config(String scope) {
        return effectiveConfigByScope(StringUtils.defaultIfBlank(scope, "GLOBAL"));
    }

    private TuningVerifyConfig effectiveConfigByScope(String scope) {
        TuningVerifyConfig c = configMapper.selectOne(new LambdaQueryWrapper<TuningVerifyConfig>().eq(TuningVerifyConfig::getScope, scope).last("limit 1"));
        if (c == null && !"GLOBAL".equals(scope)) {
            c = configMapper.selectOne(new LambdaQueryWrapper<TuningVerifyConfig>().eq(TuningVerifyConfig::getScope, "GLOBAL").last("limit 1"));
        }
        if (c == null) {
            c = new TuningVerifyConfig();
            c.setScope("GLOBAL");
            c.setMaxCases(30);
            c.setSimilarLimit(10);
            c.setRegressionLimit(15);
            c.setConcurrency(3);
            c.setCaseTimeoutS(120);
            c.setTotalTimeoutMin(30);
            c.setJudgeAnswer(1);
            c.setJudgeLlm(1);
            c.setTemperature(java.math.BigDecimal.ZERO);
            c.setRequireApproval(1);
            c.setWarnNeedNote(1);
            c.setOnlineRecheck(1);
            c.setObserveDays(7);
            c.setAlertWindowHours(24);
            c.setAlertThreshold(2);
            c.setRetainTraceDays(90);
            c.setRetainSnapshotDays(180);
            configMapper.insert(c);
        }
        return c;
    }

    /** 任务级覆盖 > 智能体级 > 全局 */
    TuningVerifyConfig effectiveConfig(TuningTask t) {
        TuningVerifyConfig base = effectiveConfigByScope(StringUtils.isBlank(t.getAiBodyCode()) ? "GLOBAL" : "AGENT:" + t.getAiBodyCode());
        TuningVerifyConfig c = JSON.parseObject(JSON.toJSONString(base), TuningVerifyConfig.class);
        if (StringUtils.isNotBlank(t.getVerifyConfig())) {
            JSONObject ov = JSON.parseObject(t.getVerifyConfig());
            if (ov.getInteger("maxCases") != null) c.setMaxCases(ov.getInteger("maxCases"));
            if (ov.getInteger("similarLimit") != null) c.setSimilarLimit(ov.getInteger("similarLimit"));
            if (ov.getInteger("regressionLimit") != null) c.setRegressionLimit(ov.getInteger("regressionLimit"));
            if (ov.getInteger("concurrency") != null) c.setConcurrency(ov.getInteger("concurrency"));
        }
        return c;
    }

    @Override
    public TuningVerifyConfig saveConfig(TuningVerifyConfig cfg) {
        if (StringUtils.isBlank(cfg.getScope())) cfg.setScope("GLOBAL");
        if (cfg.getMaxCases() == null || cfg.getMaxCases() < 1 || cfg.getMaxCases() > 200) throw new BizException(400, "max_cases 需在 1–200");
        TuningVerifyConfig existing = configMapper.selectOne(new LambdaQueryWrapper<TuningVerifyConfig>().eq(TuningVerifyConfig::getScope, cfg.getScope()).last("limit 1"));
        SaasUser u = UserThreadLocal.get();
        cfg.setUpdatedBy(u == null ? "system" : u.getUsername());
        if (existing == null) {
            cfg.setId(null);
            configMapper.insert(cfg);
        } else {
            cfg.setId(existing.getId());
            configMapper.updateById(cfg);
        }
        return cfg;
    }

    @Override
    public List<TuningVerifyConfig> configs() {
        effectiveConfigByScope("GLOBAL");
        return configMapper.selectList(new LambdaQueryWrapper<TuningVerifyConfig>().orderByAsc(TuningVerifyConfig::getScope));
    }

    // =========================================================== prompt editor

    @Override
    public JSONObject quickVerify(QuickVerifyDTO dto) {
        String group = StringUtils.defaultIfBlank(dto.getGroupName(), "prompt-main");
        TuningTask t = dto.getTaskId() == null ? null : taskMapper.selectById(dto.getTaskId());
        String question = StringUtils.defaultIfBlank(dto.getQuestion(), t == null ? null : t.getQuestion());
        if (StringUtils.isBlank(question)) throw new BizException(400, "缺少原问题");
        if (StringUtils.isBlank(dto.getContent())) throw new BizException(400, "提示词内容不能为空");
        String aiBodyCode = StringUtils.defaultIfBlank(dto.getAiBodyCode(), t == null ? null : t.getAiBodyCode());
        // 临时版本：以 "quick-" 描述保存不激活草稿（System B 版本号自动 +1），验证后保留供复用
        JSONObject req = new JSONObject();
        req.put("group_name", group);
        req.put("content", dto.getContent());
        req.put("description", "quick-verify " + (t == null ? "" : t.getTaskNo()) + " " + LocalDateTime.now());
        JSONObject created = downstream.systemBPost("/api/v1/prompts/version/create", req);
        if (created == null || created.getIntValue("code") != 200) {
            JSONObject out = new JSONObject();
            out.put("ok", false);
            out.put("message", created == null ? "System B 无响应" : created.getString("message"));
            out.put("lint", created == null ? null : (created.getJSONObject("data") == null ? null : created.getJSONObject("data").get("lint")));
            return out;
        }
        String version = created.getJSONObject("data").getString("version");
        JSONObject base;
        try {
            base = downstream.agentMeta(aiBodyCode);
        } catch (Exception e) {
            base = new JSONObject();
        }
        List<TuningChange> changes = t == null ? Collections.emptyList() : acceptedChanges(t.getId());
        JSONObject draft = applier.buildDraftMeta(base, changes, aiBodyCode);
        Map<String, String> overrides = toStrMap(draft.getJSONObject("prompt_overrides"));
        overrides.put(group, version);
        long t0 = System.currentTimeMillis();
        JSONObject resp = downstream.analyze("tuning-quick-" + (t == null ? 0 : t.getId()) + "-" + System.currentTimeMillis(), question,
                draft.getJSONObject("meta"), overrides, t == null ? 0L : t.getId(), 180);
        JSONObject out = new JSONObject();
        out.put("ok", resp != null && "success".equals(resp.getString("status")));
        out.put("version", version);
        out.put("elapsedMs", System.currentTimeMillis() - t0);
        out.put("result", TuningVerifyRunner.summarize(resp));
        return out;
    }

    @Override
    public JSONObject promptEditorContext(Long taskId, String groupName) {
        String group = StringUtils.defaultIfBlank(groupName, "prompt-main");
        JSONObject o = new JSONObject();
        JSONObject cur = downstream.promptCurrent(group);
        o.put("group", group);
        o.put("current", cur);
        if (taskId != null) {
            TuningTask t = mustTask(taskId);
            o.put("question", t.getQuestion());
            ChatErrorDiagnosis d = t.getDiagnosisId() == null ? null : diagnosisMapper.selectById(t.getDiagnosisId());
            o.put("rootCause", d == null ? null : d.getRootCause());
            o.put("errorStepId", d == null ? null : d.getErrorStepId());
            ChatAnalysisTrace tr = traceByChat(t.getChatId());
            JSONArray steps = tr == null ? new JSONArray() : ChatQualityServiceImpl.parseJsonArr(tr.getDecompositionSteps());
            JSONArray failed = new JSONArray();
            for (int i = 0; i < steps.size(); i++) {
                JSONObject s = steps.getJSONObject(i);
                if (d != null && d.getErrorStepId() != null && d.getErrorStepId().equals(s.getString("step_id"))) failed.add(s);
            }
            o.put("failedSteps", failed.isEmpty() ? steps : failed);
            // 规则条目定位：根因关键词
            List<String> anchors = new ArrayList<>();
            String root = StringUtils.defaultString(d == null ? "" : d.getRootCause());
            for (String kw : List.of("累计", "快照", "同比", "环比", "率", "排名", "占比", "时间范围", "分组")) if (root.contains(kw)) anchors.add(kw);
            o.put("anchors", anchors);
            TuningChange pc = changeMapper.selectOne(new LambdaQueryWrapper<TuningChange>().eq(TuningChange::getTaskId, taskId)
                    .eq(TuningChange::getAssetType, "prompt-main".equals(group) ? "PROMPT_DECOMPOSE" : "PROMPT_SUMMARY").last("limit 1"));
            o.put("change", pc);
        }
        return o;
    }

    // ========================================================= notifications

    @Override
    public List<JSONObject> notifications(boolean unreadOnly) {
        return notificationMapper.selectList(new LambdaQueryWrapper<TuningNotification>().eq(unreadOnly, TuningNotification::getReadFlag, 0)
                        .orderByDesc(TuningNotification::getCreatedAt).last("limit 100"))
                .stream().map(n -> Jsons.obj(n)).collect(Collectors.toList());
    }

    @Override
    public void markNotificationRead(Long id) {
        TuningNotification n = notificationMapper.selectById(id);
        if (n != null) {
            n.setReadFlag(1);
            notificationMapper.updateById(n);
        }
    }

    @Override
    public int observeCheck() {
        int alerts = 0;
        for (TuningTask t : taskMapper.selectList(new LambdaQueryWrapper<TuningTask>().eq(TuningTask::getStatus, PUBLISHED).ge(TuningTask::getObserveUntil, LocalDateTime.now()))) {
            TuningVerifyConfig cfg = effectiveConfig(t);
            LocalDateTime since = LocalDateTime.now().minusHours(cfg.getAlertWindowHours());
            List<ChatFeedback> fbs = feedbackMapper.selectList(new LambdaQueryWrapper<ChatFeedback>().eq(ChatFeedback::getRating, -1)
                    .eq(StringUtils.isNotBlank(t.getAiBodyCode()), ChatFeedback::getAiBodyCode, t.getAiBodyCode()).ge(ChatFeedback::getCreatedAt, since)
                    .ge(ChatFeedback::getCreatedAt, t.getPublishedAt()));
            long sameType = fbs.stream().filter(f -> ChatQualityServiceImpl.parseArr(f.getErrorTypes()).stream()
                    .anyMatch(x -> x.startsWith(StringUtils.defaultString(t.getPrimaryErrorType()).split("\\.")[0]))).count();
            if (sameType >= cfg.getAlertThreshold()) {
                JSONObject alert = new JSONObject();
                alert.put("at", LocalDateTime.now().toString());
                alert.put("count", sameType);
                alert.put("windowHours", cfg.getAlertWindowHours());
                alert.put("message", cfg.getAlertWindowHours() + "h 内新增同类👎 " + sameType + " 条（阈值 " + cfg.getAlertThreshold() + "）");
                JSONObject prev = (JSONObject) ChatQualityServiceImpl.parseJson(t.getObserveAlert());
                if (prev == null || prev.getLongValue("count") != sameType) {
                    t.setObserveAlert(alert.toJSONString());
                    taskMapper.updateById(t);
                    notify("OBSERVE_ALERT", t, "观察期告警：" + t.getTaskNo(), alert.getString("message"), "QUALITY_ADMIN");
                    alerts++;
                }
            }
        }
        return alerts;
    }

    // ============================================================== helpers

    private TuningTask mustTask(Long id) {
        TuningTask t = taskMapper.selectById(id);
        if (t == null) throw new BizException(404, "任务不存在");
        return t;
    }

    private List<TuningChange> acceptedChanges(Long taskId) {
        return changeMapper.selectList(new LambdaQueryWrapper<TuningChange>().eq(TuningChange::getTaskId, taskId).eq(TuningChange::getAccepted, 1).orderByAsc(TuningChange::getSeq));
    }

    private ChatAnalysisTrace traceByChat(String chatId) {
        if (chatId == null) return null;
        return traceMapper.selectOne(new LambdaQueryWrapper<ChatAnalysisTrace>().eq(ChatAnalysisTrace::getChatId, chatId).last("limit 1"));
    }

    private void audit(TuningTask t, String action, String before, String after, String detail) {
        TuningAuditLog a = new TuningAuditLog();
        a.setTaskId(t.getId());
        a.setAction(action);
        SaasUser u = UserThreadLocal.get();
        a.setOperator(u == null ? "system" : u.getUsername());
        a.setBeforeStatus(before);
        a.setAfterStatus(after);
        a.setDetail(abbreviate(detail, 2000));
        auditMapper.insert(a);
    }

    private void notify(String type, TuningTask t, String title, String content) {
        notify(type, t, title, content, "SUPER_ADMIN");
    }

    private void notify(String type, TuningTask t, String title, String content, String role) {
        TuningNotification n = new TuningNotification();
        n.setType(type);
        n.setTaskId(t.getId());
        n.setTitle(abbreviate(title, 256));
        n.setContent(abbreviate(content, 2000));
        n.setTargetRole(role);
        n.setReadFlag(0);
        notificationMapper.insert(n);
    }

    private static String abbreviate(String s, int n) {
        if (s == null) return null;
        return s.length() > n ? s.substring(0, n) : s;
    }

    /** 避免跨包调用 AssetApplier 的包级方法 */
    static final class AssetApplierCode {
        static String codeOf(String label) {
            if (StringUtils.isBlank(label)) return "";
            return label.trim().split("\\s+")[0];
        }
    }
}
