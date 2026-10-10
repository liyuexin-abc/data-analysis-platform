# 需求交接文档 — 问答质量管理 + 自动调优

> 交接给：接手测试验证的智能体 / 工程师
> 本文只回答两个问题：**① 这个需求是什么、做到了什么程度；② 仓库里哪些代码是本需求的、哪些是原产品的，边界在哪。**
> 环境搭建、mock 契约、测试脚本的细节不在本文展开，见 §5 的指引链接。

---

## 1. 需求是什么

### 1.1 背景
原产品是一个「智能问数」平台：用户用自然语言提问 → 召回服务（recall-service）选表 → 算法 System B 拆解问题并生成取数步骤 → chat-server 取数并总结 → 前端展示。
**原产品缺少**：用户对答案的反馈渠道；管理员定位"答错在哪一环"的工具；修正元数据/提示词后无法在上线前验证效果；无审批与回滚。

### 1.2 本需求交付的能力（两份需求文档）

| 文档 | 能力 | 一句话 |
|---|---|---|
| `requirements/01-问答质量管理方案.md` | **用户反馈 + 管理端错误定位** | 用户对一次问答 👍/👎 并勾选错误类型；管理员在「问答诊断」中看到 5 环节链路（召回→拆解→取数映射→执行→总结）与 System B 日志，填写根因定位表单 |
| `requirements/03-自动调优设计.md` | **自动调优闭环** | 定位后按规则 R1–R12 生成"改哪个资产、改成什么"的建议 → 创建调优任务 → 在**草稿态**对原问题 + 同类问题 + 回归集做前后对比验证 → PASS 后提交**超级管理员审批** → 通过即发布到真实资产并线上复验，进入 7 天观察期 → 可一键回滚 |

`requirements/02-原型说明.md` 是交互原型说明（`docs/prototype/`），不是功能需求。

### 1.3 已确认的关键决策（评审结论，实现已遵循）
- 发布必须经**超级管理员**审批；管理员只能提交 / 撤回。
- 验证集总上限默认 **30** 题，可全局 / 按智能体配置。
- 提示词类调优必须通过**编辑器**产出草稿版本（不直接写文本）。
- 观察期告警：**24 小时内同类 👎 ≥ 2** 条触发通知。
- 调优"草稿态"不写真实资产：元数据以内存覆盖方式传给 System B；提示词以未激活版本 + `prompt_overrides` 方式生效。

### 1.4 完成度
| 层 | 状态 | 证据 |
|---|---|---|
| 算法 System B | 完成 | `tests/test_tuning_support.py` 13 passed |
| 后端 chat-server（采集 trace） | 完成 | 编译通过；trace 落库由 seed 数据模拟 |
| 后端 bi-manager（核心业务） | 完成 | 单测 11 passed；API 功能测试 **64/64**（`handover/tests/FUNC_TEST_REPORT.md`） |
| 前端 | 完成 | Playwright UI E2E 主流程 13 步 + 👎 弹窗 + 提示词编辑器，0 JS error；截图 `docs/prototype/screenshots/impl/` |
| 未做 / 降级 | `RECALL_CONFIG` 资产类型仅记录不自动下发；System B 的 LLM 答案判定（`judge_llm`）为预留开关，当前只做关键词判定 | |

---

## 2. 代码边界：本需求 vs 原产品

**原产品基线**：Git 提交 `c9ceb58`（PR #1 合并点）。基线之后的全部改动即本需求。
精确文件级清单见 `handover/FILE_MANIFEST.md`（由 `git diff c9ceb58..HEAD` 生成）。

### 2.1 总览

| | 文件数 | 说明 |
|---|---|---|
| **A. 新增文件** | **81** | 纯需求代码。全部位于独立目录 / 独立包，**整体删除后原产品功能不受影响** |
| **B. 修改的原产品文件** | **17** | 只有"注入点"：注册、调用一行、加一个端点。合计 **+424 / −28 行**，每处均有注释 `问答质量管理` / `调优` |
| C. 需求 / 文档 / 交接 | — | `requirements/`、`docs/`、`handover/`、根 `README.md` |

### 2.2 新增代码落位（A 类）— 一眼可辨

```
backend/bi-manager/src/
├── main/java/com/quality/            ← 新包，本需求后端核心（55 文件）
│   ├── controller/  ChatQualityController · TuningController
│   ├── service/     ChatQualityService · TuningService · RegressionService · TuningVerifyRunner · ObserveScheduler (+impl/)
│   ├── engine/      SuggestionEngine(R1–R12) · VerifyJudge · AssetApplier      ← 纯函数，可单测
│   ├── client/      DownstreamClient(调 System B / chat-server / recall) · QualityProperties
│   ├── entity/ ×15  mapper/ ×15  dto/ ×9  util/Jsons
├── main/resources/db/quality.sql     ← 新建 15 张表 + 种子（独立表，不改原表结构）
├── main/resources/db/dev_minimal.sql ← 仅沙箱
└── test/java/com/quality/engine/     ← 单测 ×2

backend/chat-server/src/main/java/com/chatbi/chat/
├── entity/ChatAnalysisTrace · ChatStepTrace
├── mapper/ChatAnalysisTraceMapper · ChatStepTraceMapper
└── service/ChatTraceService (+impl/)            ← 把一次问答的结构化结果写入 trace 表

algorithm/analyze-streaming/
├── system_b/utils/tuning_support.py             ← 调优模式 / prompt_overrides / 结构化字段 / lint / diff
└── tests/test_tuning_support.py

frontend/chatbi-new/src/
├── api/qualityManager/qualityAPI.js
├── views/newBI/feedbackBar/index.vue            ← 应用端 👍👎 组件
└── views/qualityManager/                        ← 管理端整页（index + 11 components）
```

### 2.3 对原产品的修改（B 类）— 逐文件说明

| 原产品文件 | 改动 | 行数 | 性质 |
|---|---|---|---|
| **bi-manager** | | | |
| `com/BiApplication.java` | `@MapperScan` 增加 `com.quality.mapper` | +1/−1 | 注册 |
| `resources/application.yml` | 追加 `quality:` 配置段（下游 URL、线程池、超管名单） | +10/−1 | 配置 |
| **chat-server** | | | |
| `feign/client/ResolverClient.java` | `analyze` 返回类型 `String` → `JSONObject`（为拿到 System B 新增的结构化字段） | +3/−2 | 类型调整，调用方已同步 |
| `controller/ChatQueryController.java` | analyze 返回后调用 `chatTraceService.saveAnalysisTrace(...)`，try/catch 包裹，**失败不影响主流程** | +12/−1 | 注入点 |
| `controller/MetricQueryController.java` | 取数后写 `chat_step_trace`（含 unmatched_columns），try/catch 包裹 | +45/−0 | 注入点 |
| `controller/MetadataController.java` + `service/MetadataService(Impl).java` | 新增 `GET /api/chat-server/metadata/agent?code=`，复用原有 `buildBusinessContext` 逻辑 | +37/−0 | 新增端点，不改旧端点 |
| **System B** | | | |
| `system_b/app.py` | `/api/v1/analyze` 识别调优模式（跳过召回、不清上下文、应用 `prompt_overrides`）、响应追加结构化字段；新增 `/prompts/version/create|diff`、`/prompts/lint`、`/logs/by-request` | +223/−17 | 主要改动点；非调优请求路径行为不变 |
| `system_b/utils/prompt_version_manager.py` | `get_active_version()` 优先读线程局部覆盖 | +18 | 覆盖钩子 |
| `system_b/execution/dag_engine.py` | worker 线程继承 `prompt_overrides` | +6 | 透传 |
| `system_b/utils/db.py` | `user_operation_log` 增加 `request_id` 列（自动迁移）并写入 | +39/−4 | 日志可按 request 检索 |
| `system_b/utils/log_manager.py` | 日志按 request_id 落文件 | +6/−2 | 配套 |
| **前端** | | | |
| `utils/common.js` · `views/index.vue` · `components/LeftMenu/index.vue` | 注册页面 `qualityManager` 与菜单项「系统配置 → 问答质量管理」 | +1 ×3 | 注册 |
| `views/newBI/queryPage/index.vue` | 答案卡片底部挂 `<feedback-bar>`（`turn.status==='done'` 时显示）+ `lastAnswerOf()` | +21 | 注入点 |

### 2.4 数据层边界
- **新建表 15 张**（`quality.sql`）：`chat_analysis_trace, chat_step_trace, chat_feedback, chat_error_diagnosis, chat_error_type_dict, tuning_task, tuning_change, asset_snapshot, tuning_verify_report, tuning_verify_case, regression_case, tuning_verify_config, tuning_rule, tuning_audit_log, tuning_notification`。**不修改任何原产品表结构**。
- **读原产品表**：`dcar_ai_body, dcar_ai_body_relation, olap_table_pro, olap_basic_pro, olap_table_field_mapping, dcar_chat_model_qa`（用于建议生成与资产发布）。
- **写原产品表**（仅在"发布 / 回滚"时，且每次写前有快照）：`olap_table_pro.note`、`olap_basic_pro.alias`、`olap_table_field_mapping.summary_key`、`dcar_ai_body_relation`(绑表)、`dcar_ai_body.description`(业务知识)、`dcar_chat_model_qa.feedback/reason`(反馈镜像)。
- `dev_minimal.sql` 仅为沙箱补缺表 / 缺列，**生产勿执行**。

### 2.5 与原产品的运行时交互（本需求 → 原产品）
| 调用方向 | 接口 | 是否可 mock |
|---|---|---|
| bi-manager → System B | `POST /api/v1/analyze`（调优模式）、`/prompts/*`、`/logs/by-request` | ✅ `handover/mock/mock_system_b.py` |
| bi-manager → chat-server | `GET /api/chat-server/metadata/agent` | ✅ 同上 |
| bi-manager → recall-service | `POST /api/metadata/sync`（发布后，失败仅告警） | ✅ 同上 |
| chat-server → bi-manager 的表 | 直接写 `chat_analysis_trace / chat_step_trace`（同库） | 用 seed 数据替代 |

**结论**：独立验证本需求只需启动 **bi-manager + mock + 数据库**（可选前端），原产品其它服务一律不需要。

### 2.6 如何从仓库"剥离"本需求代码
```bash
# 列出全部需求文件（A 类 + B 类）
git diff --name-only c9ceb58 HEAD -- backend algorithm frontend
# 只看对原产品的改动内容
git diff c9ceb58 HEAD -- $(git diff --name-only --diff-filter=M c9ceb58 HEAD -- backend algorithm frontend)
```

---

## 3. 功能模块地图（需求 → 代码）

| 需求能力 | 后端入口（bi-manager） | 前端 | 需求章节 |
|---|---|---|---|
| 用户 👍👎 反馈 | `ChatQualityController` `/v1/chat/feedback/*` → `ChatQualityServiceImpl.submitFeedback` | `feedbackBar/index.vue` | 01 §3 |
| 反馈列表 / 状态流转 | `/v1/chat/feedback/page`、`/{id}/status` | `FeedbackList.vue` | 01 §4.1 |
| 问答诊断（5 环节链路 + 日志） | `/v1/chat/diagnosis/page`、`/trace/{chatId}`、`/system-b-log` → `ChatQualityServiceImpl.trace` | `DiagnosisList.vue`、`DiagnosisDetail.vue` | 01 §4.2–4.3 |
| 根因定位表单 | `/v1/chat/diagnosis/save` | `DiagnosisDetail.vue` 右栏 | 01 §4.4 |
| 调优建议 R1–R12 | `TuningController` `/v1/tuning/suggest` → `SuggestionEngine` | `SuggestionTable.vue` | 03 §4 |
| 调优任务状态机 | `/v1/tuning/task/*` → `TuningServiceImpl`（execute / verify / submit / approve / rollback / cancel） | `TuningList.vue`、`TuningDetail.vue` | 03 §5–7 |
| 草稿验证（前后对比） | `TuningVerifyRunner` + `VerifyJudge` + `AssetApplier.buildDraftMeta` | `TuningDetail.vue` 验证报告区 | 03 §6 |
| 超级管理员审批 | `/approve`、`/approval/pending`、`requireSuperAdmin()` | `ApprovalCenter.vue` | 03 §7 |
| 发布 / 线上复验 / 回滚 | `TuningServiceImpl.doPublish / rollback` + `AssetApplier.publish / rollback` | `TuningDetail.vue` 操作区 | 03 §8–9 |
| 提示词编辑器 | `/prompt/editor-context`、`/prompt/quick-verify`；System B `/prompts/version/create|lint` | `PromptEditor.vue` | 03 §10 |
| 回归集 | `/v1/tuning/regression/*` → `RegressionServiceImpl` | `RegressionList.vue` | 03 §6.3 |
| 验证配置（GLOBAL / AGENT） | `/v1/tuning/config*` | `QualitySettings.vue` | 03 §11 |
| 观察期告警 / 通知 | `ObserveScheduler`、`/notifications` | 页面右上铃铛 | 03 §9.2 |
| 质量统计 | `/v1/chat/quality/stats` | `QualityStats.vue` | 01 §4.5 |

---

## 4. 已知差异与注意事项

1. **已修复的竞态**：`execute()` 曾在事务内启动异步验证线程，下游很快时任务会卡在 `VERIFYING`；现改为事务提交后启动（`TuningServiceImpl.startVerify`）。
2. 权限双重校验：前端按 `config.js` 的 `APP_Super_Manager / APP_Manager` 控制按钮显隐；后端按 `quality.super-admins` 对审批 / 回滚 / 改配置返回 403。两处名单需保持一致。
3. 所有 JSON 列在实体中为 `String`，对外输出时已在 service 层展开；日期统一 `yyyy-MM-dd HH:mm:ss`。
4. 沙箱中 System B / chat-server 为 mock，验证结论恒为 PASS；接真实服务后结论取决于模型输出。
5. 前端 production 构建需 >2 GB 内存；低内存用 `--mode development`。

---

## 5. 进一步资料

| 想做什么 | 看哪里 |
|---|---|
| 搭独立验证环境（DB + mock + bi-manager） | `handover/bootstrap.sh`、`handover/seed_minimal.sql`、`handover/mock/*` |
| 看 mock 接口返回 / 造异常场景 | `handover/mock/mock_system_b.py`（每个端点一行，直接改返回值） |
| 跑 API 功能测试 | `python3 handover/tests/func_test_quality.py http://127.0.0.1:8591` |
| 跑 UI E2E / 手工验收 | `handover/tests/e2e_*.py`；`docs/dev/SANDBOX_ACCEPTANCE.md` |
| 看接口字段与状态机细节 | `requirements/03-自动调优设计.md` §5–§7；`TuningController.java` |
| 精确文件清单 | `handover/FILE_MANIFEST.md` |
