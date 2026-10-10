# 交接文档 — 问答质量管理 + 自动调优（独立测试验证版）

> 面向对象：接手测试验证的智能体 / 工程师。
> 目标：拿到本仓库源码后，**不部署整个数据分析平台**，仅用本需求涉及的源码 + 模拟接口数据，即可独立启动、测试、验证本需求。
> 需求原文：`requirements/01-问答质量管理方案.md`、`requirements/03-自动调优设计.md`（`02` 为原型说明）。

---

## 0. 一句话理解需求

用户对一次问答点 👎 并选择错误类型 → 管理员在「问答诊断」中查看 5 环节链路并定位根因 → 系统按规则 R1–R12 生成调优建议（改表描述 / 实体别名 / 维度枚举 / 聚合方式 / 业务知识 / 智能体绑表 / 提示词）→ 创建调优任务，在**草稿态**对原问题 + 同类问题 + 回归集做前后对比验证 → 验证 PASS 后提交**超级管理员审批** → 通过即发布到真实资产并做线上复验，进入 7 天观察期 → 可一键回滚。

---

## 1. 本需求涉及的源码（完整清单）

### 1.1 后端 bi-manager（核心，Spring Boot 3.5 + MyBatis-Plus，Java 17/21）
路径前缀：`backend/bi-manager/src/`

| 目录 / 文件 | 职责 |
|---|---|
| `main/java/com/quality/controller/ChatQualityController.java` | `/api/v1/chat/**`：错误类型字典、反馈、trace、诊断、统计 |
| `main/java/com/quality/controller/TuningController.java` | `/api/v1/tuning/**`：建议、任务状态机、验证、审批、回滚、配置、回归集、通知、提示词编辑器；含**超级管理员校验** `requireSuperAdmin()` |
| `main/java/com/quality/service/impl/ChatQualityServiceImpl.java` | 反馈提交（覆盖写 + 镜像 `dcar_chat_model_qa` + 👍 自动回归沉淀）、诊断列表/详情（5 环节拼装）、保存定位、统计 |
| `main/java/com/quality/service/impl/TuningServiceImpl.java` | 任务状态机、并发守卫、执行（快照 + 草稿 meta）、验证集生成、审批/撤回/驳回、发布（资产写入 + recall 同步 + 线上复验 + 观察期）、回滚（冲突检测）、配置、通知、提示词 quick-verify / editor-context |
| `main/java/com/quality/service/impl/RegressionServiceImpl.java` | 回归用例 CRUD / 从 trace / 从诊断沉淀 / 抽样 |
| `main/java/com/quality/service/TuningVerifyRunner.java` | 异步验证线程池：逐用例调 System B `analyze`（调优模式）→ `VerifyJudge` 判定 → 汇总报告；发布后线上复验 |
| `main/java/com/quality/service/ObserveScheduler.java` | 每 30 min 巡检观察期任务：24h 内同类 👎 ≥ 2 → 告警通知 |
| `main/java/com/quality/engine/SuggestionEngine.java` | **纯函数** 规则引擎 R1–R12（输入诊断 + trace + 资产上下文 → 变更建议） |
| `main/java/com/quality/engine/VerifyJudge.java` | **纯函数** 判定：表 / 指标 / 维度 / 状态 / 答案关键词 → pass；verdict（FIXED/UNFIXED/DEGRADED/…）；结论 PASS / PASS_WITH_WARN / FAIL |
| `main/java/com/quality/engine/AssetApplier.java` | 资产快照 / 发布 / 回滚（6 种 bi-manager 资产 + 2 种提示词）；草稿 meta 内存覆盖 |
| `main/java/com/quality/client/DownstreamClient.java` | 下游 HTTP：System B / chat-server / recall（**全部可 mock**，见 §3） |
| `main/java/com/quality/client/QualityProperties.java` | `quality.*` 配置 |
| `main/java/com/quality/entity/*` (15) · `mapper/*` (15) · `dto/*` (9) · `util/Jsons.java` | 实体 / Mapper / 入参 / 日期序列化 |
| `main/resources/db/quality.sql` | **15 张表 DDL + 种子**（错误类型字典 8、规则 12、GLOBAL 验证配置） |
| `main/resources/application.yml`（`quality:` 段） · `application-sandbox.yml` | 下游地址、线程池、超管名单；沙箱 profile（本地库、关 nacos） |
| `main/java/com/BiApplication.java` | `@MapperScan` 增加 `com.quality.mapper` |
| `test/java/com/quality/engine/{SuggestionEngineTest,VerifyJudgeTest}.java` | 11 个单元测试 |

依赖的平台公共类（已在仓库内，无需额外服务）：`com.common.{base.UserThreadLocal, exception.BizException, models.SaasUser, result.R, result.PageResult, filter.LoginFilter}`、`com.bi.entity/mapper.{ChatModelQA, OlapBasicPro, OlapTablePro, OlapTableFieldMapping}`。

### 1.2 后端 chat-server（仅"采集侧"，验证本需求时**可不启动**）
| 文件 | 职责 |
|---|---|
| `entity/{ChatAnalysisTrace,ChatStepTrace}.java` · `mapper/{…}Mapper.java` · `service/ChatTraceService.java` · `service/impl/ChatTraceServiceImpl.java` | 一次问答后写 `chat_analysis_trace` / `chat_step_trace` |
| `controller/ChatQueryController.java`、`controller/MetricQueryController.java` | 调用点（analyze 返回后 / 取数后） |
| `feign/client/ResolverClient.java` | 返回类型改 `JSONObject` 以拿到结构化字段 |
| `controller/MetadataController.java` + `service/MetadataService(Impl).java` | `GET /api/chat-server/metadata/agent?code=`：给调优验证提供智能体元数据 |

> 独立验证时，trace 数据由 `handover/seed_minimal.sql` 直接灌入，`/metadata/agent` 由 mock 提供。

### 1.3 算法 System B（`algorithm/analyze-streaming/system_b`，验证本需求时**可用 mock 代替**）
| 文件 | 职责 |
|---|---|
| `utils/tuning_support.py` | 调优模式识别、`prompt_overrides` 线程局部、结构化字段抽取、提示词 lint / diff |
| `app.py` | `/api/v1/analyze` 识别调优模式；新增 `/api/v1/prompts/version/create|diff`、`/api/v1/prompts/lint`、`/api/v1/logs/by-request` |
| `utils/prompt_version_manager.py` · `execution/dag_engine.py` · `utils/db.py` · `utils/log_manager.py` | 版本覆盖透传、request_id 落日志 |
| `tests/test_tuning_support.py` | 13 个 pytest 用例（**纯 Python，无外部依赖，可直接跑**） |

### 1.4 前端 chatbi-new（Vue 2.6 + Element UI）
路径前缀：`frontend/chatbi-new/src/`

| 文件 | 职责 |
|---|---|
| `api/qualityManager/qualityAPI.js` | 全部接口封装 + 常量（任务状态 / 判定 / 反馈状态 / 资产类型） |
| `views/newBI/feedbackBar/index.vue` | 应用端 👍👎 组件（👎 弹窗选错误类型 + 描述） |
| `views/newBI/queryPage/index.vue` | 挂载点（答案卡片底部，`turn.status==='done'`） |
| `views/qualityManager/index.vue` | 管理端页面：7 个 Tab + 铃铛通知 |
| `views/qualityManager/components/FeedbackList.vue` | 用户反馈 |
| `…/DiagnosisList.vue` · `DiagnosisDetail.vue` | 问答诊断列表 / 三栏抽屉（左信息、中 5 环节、右定位表单） |
| `…/SuggestionTable.vue` | 建议/变更表格（可勾选、可编辑、手动追加 11 种资产） |
| `…/TuningList.vue` · `TuningDetail.vue` | 任务列表 / 详情抽屉（步骤条、变更、验证报告、审计、操作区） |
| `…/PromptEditor.vue` | 全屏提示词编辑器（参考 / 带行号编辑 / 实时 diff、lint、快速验证、保存草稿版本） |
| `…/ApprovalCenter.vue` · `RegressionList.vue` · `QualityStats.vue` · `QualitySettings.vue` | 审批中心 / 回归集 / 质量统计 / 设置 |
| `utils/common.js` · `views/index.vue` · `components/LeftMenu/index.vue` | 菜单注册「系统配置 → 问答质量管理」 |

权限约定（前端）：`public/config.js` 的 `APP_Super_Manager='admin'`（超管）、`APP_Manager='yangbin'`（管理员）；`utils/auth.js` 的 `isAdmin()/isSuperAdmin()` 读 `localStorage['Admin-User']`。

### 1.5 交接包（`handover/`，本目录）
| 文件 | 用途 |
|---|---|
| `HANDOVER.md` | 本文 |
| `seed_minimal.sql` | 上游业务表最小 DDL + 演示数据（1 智能体 / 2 表 / 1 指标 1 维度 / 2 轮问答 / 2 条 👎） |
| `bootstrap.sh` | 一键：建库 → 灌 DDL/种子 → 起 mock → 起 bi-manager →（可选）起前端代理 |
| `mock/mock_system_b.py` | **模拟 System B + chat-server + recall** 全部下游接口（Flask，:5055） |
| `mock/fe_static_proxy.py` | 前端静态服务 + API 反代 + **模拟登录** `/upc/user/login`（:8600） |
| `mock/gen_token.py` | 直接签发 bi-manager 可用的 JWT |
| `tests/func_test_quality.py` | **API 级功能测试套件**（~60 条断言，正向闭环 + 状态守卫 + 权限 + 校验） |
| `tests/e2e_login.py` · `e2e_quality_ui.py` · `e2e_quality_ui_prompt.py` | Playwright UI E2E（需构建前端） |

---

## 2. 最小运行拓扑（独立验证）

```
 ┌───────────────┐  /api/*  ┌─────────────────────┐  HTTP  ┌──────────────────────────┐
 │ 前端 dist(可选)│ ───────▶ │ bi-manager  :8591   │ ─────▶ │ mock_system_b.py  :5055  │
 │ fe_static_proxy│          │ (com.quality 模块)  │        │ = System B + chat-server │
 │  :8600 + 模拟登录│          └──────────┬──────────┘        │   + recall 的全部下游接口 │
 └───────────────┘                     │ JDBC                 └──────────────────────────┘
                                       ▼
                             MySQL/MariaDB  quality.sql + seed_minimal.sql
```

**不需要**：nacos、redis、minio、permission 服务、semantic-server、真实 System B / 大模型、recall-service、chat-server。

### 2.1 环境依赖
JDK 17+（已在 JDK 21 验证）、Maven 3.8+、MySQL 8 / MariaDB 10.6+、Python 3.10+（`pip install flask`；UI E2E 另需 `pip install playwright && playwright install chromium`）、Node 16+（仅构建前端时）。

### 2.2 一键启动
```bash
# 仓库根目录
DB_USER=root DB_PASS=xxx DB_NAME=quality_test bash handover/bootstrap.sh
# 输出 ready 后：
TOKEN=$(python3 handover/mock/gen_token.py admin)
curl -s -H "Authorization: Bearer $TOKEN" -H "tenantid: 1" http://127.0.0.1:8591/api/v1/chat/error-types
```
手工等价步骤（如脚本不适用）：
1. `mysql -u.. -p.. --default-character-set=utf8mb4 DB < backend/bi-manager/src/main/resources/db/quality.sql`，再 `< handover/seed_minimal.sql`（**务必 utf8mb4**，否则中文乱码）。
2. `python3 handover/mock/mock_system_b.py`（:5055）。
3. `cd backend/bi-manager && mvn -q -DskipTests package`，然后以环境变量指定数据源与下游：
   `SPRING_DATASOURCE_URL=... SPRING_DATASOURCE_USERNAME=... SPRING_DATASOURCE_PASSWORD=... QUALITY_SYSTEM_B_URL=http://127.0.0.1:5055 QUALITY_CHAT_SERVER_URL=http://127.0.0.1:5055 QUALITY_RECALL_URL=http://127.0.0.1:5055 java -jar target/bi-backend-1.0.0-SNAPSHOT.jar --spring.profiles.active=sandbox`
4. （可选）前端：`cd frontend/chatbi-new && npm i && npx vue-cli-service build --mode development --no-module --dest /tmp/fe_dist`，再 `FE_DIST=/tmp/fe_dist python3 handover/mock/fe_static_proxy.py`，浏览器打开 `http://127.0.0.1:8600`，账号 `admin / yangbin / tester`（任意密码）。

### 2.3 认证约定
- 所有 `/api/**` 需要头：`Authorization: Bearer <JWT>` 与 `tenantid: 1`。
- JWT：HS256，密钥 `jwt.secret`（默认 `chatbi-jwt-secret-key-for-production-2026`），claims `user_id`、`sub`(=username)。`gen_token.py` 与 `fe_static_proxy.py` 均按此签发。
- 角色：`admin` = 超级管理员（可审批 / 发布 / 回滚 / 改验证配置，由 `quality.super-admins` 控制）；`yangbin` = 管理员；`tester` = 普通用户（只应提交反馈）。

---

## 3. 模拟接口契约（mock_system_b.py 已实现，可按需改返回值造场景）

### 3.1 System B（bi-manager → System B）
| 方法 路径 | 请求要点 | 返回（mock 现状） | 用途 |
|---|---|---|---|
| `POST /api/v1/analyze` | `request_id`(以 `tuning-`/`recheck-` 开头)、`query`、`database_meta`(草稿 meta)、`prompt_overrides{group:version}`、头 `X-Tuning-Task` | `{request_id,status:'success',answer,used_tables:[...],resolved_metrics,resolved_dims,failed_steps:[],steps:[...],prompt_version,elapsed_ms}`；**若 meta 中含 `view_by_voltage` 则选它，否则选 `view_fenqu`**（用于让"变更后"命中应选表） | 验证用例执行、快速验证、线上复验 |
| `POST /api/v1/prompts/groups` | `{}` | `[{id,name:'prompt-main'},{id,name:'prompt-summary'}]` | 编辑器 |
| `POST /api/v1/prompts/current` | `{group_id/group_name}` | `{version:'v1.0.5',content:'…{query}…'}` | 编辑器基线 |
| `POST /api/v1/prompts/list` / `version/content` | — | 版本列表 / 指定版本内容 | 编辑器 |
| `POST /api/v1/prompts/lint` | `{content}` | `{ok,errors[],warnings[],placeholders[]}` | 编辑器校验 |
| `POST /api/v1/prompts/version/create` | `{group_name,content,description,activate:false}` | `{version:'v1.0.6',is_active:false,diff_summary}` | 保存草稿版本 / 快速验证临时版本 |
| `POST /api/v1/prompts/switch` | `{group_name,version}` | `{code:200}` | 发布 / 回滚提示词 |
| `POST /api/v1/logs/by-request` | `{request_id:'S1@C1'}` | `{code:200,data:{content,log_path}}` | 诊断页「System B 日志」 |

### 3.2 chat-server（bi-manager → chat-server）
| `GET /api/chat-server/metadata/agent?code=xs_agent` | → `{available_metrics[{metric_code,description}],available_dimensions[{dimension_code,description}],table_summaries[{table_name,description,columns}],business_context}` | 构造验证用的 database_meta 基线（草稿变更叠加其上） |

### 3.3 recall-service（bi-manager → recall）
| `POST /api/metadata/sync` | → `{code:200}` | 发布后触发召回元数据同步（失败仅告警，不阻断） |

> 造异常场景：把 `analyze` 返回 `status:'failure'` 或 `used_tables:['view_fenqu']` → 验证结论 FAIL，提交审批应被拒（400）；让 `analyze` sleep 超过 `case_timeout_s` → 用例 ERROR；让 `/prompts/switch` 返回非 200 → 发布部分失败（`applyStatus=FAILED`）。

---

## 4. 对外接口清单（被测对象，bi-manager）

基址 `http://127.0.0.1:8591/api`。响应统一 `{code,message,data,success}`；业务错误 `code` = 400/403/404/409/502，HTTP 仍 200。

### 4.1 `/v1/chat`（反馈 / 诊断）
| 方法 | 路径 | 说明 | 关键校验 |
|---|---|---|---|
| GET | `/error-types` | 错误类型字典（8 条，含 scope / pipeline_stage） | |
| POST | `/feedback/submit` | 提交/覆盖反馈 `{chatSessionId,chatId,aiBodyCode?,question?,rating:1\|-1,errorTypes[],description?}` | rating 仅 ±1；👎 必选 ≥1 错误类型；同用户同 chat 覆盖；👍 自动沉淀回归 |
| GET | `/feedback/mine?chatId=` | 当前用户对该问答的反馈 | |
| GET | `/feedback/page` | 管理端分页 `rating/status/errorType/aiBodyCode/keyword/startTime/endTime` | |
| PUT | `/feedback/{id}/status` | `{status}` 0 待处理 1 已定位 2 已修复 3 已忽略 | |
| GET | `/trace/{chatId}` | `{trace,steps,items,feedbacks,diagnosis,stages[5]}` | 不存在返回 trace=null 不报错 |
| GET | `/trace/{chatId}/system-b-log` | `{content,log_path}` | trace 不存在 404 |
| GET | `/diagnosis/page` | `quick=feedback\|failed\|undiagnosed`；行含 `autoHint`、`userErrorTypes`、`downCount`、`fixStatus` | |
| POST | `/diagnosis/save` | `{chatId,primaryErrorType*,secondaryErrorTypes[],errorStepId,expectedTable,expectedEntities[{type,code,alias}],rootCause,fixMethod,expectedAnswerKeywords[],addRegression}` → `{diagnosis}` | 关联反馈 → 状态 1 + diagnosisId |
| PUT | `/diagnosis/{chatId}/fix-status` | `{fixStatus}` 0/1/2 | ≥1 时反馈 → 已修复 |
| GET | `/quality/stats?days=&aiBodyCode=` | 总量 / 👍👎 / 失败 / 错误类型分布 / 修复状态 | |

### 4.2 `/v1/tuning`（调优）
| 方法 | 路径 | 说明 / 守卫 |
|---|---|---|
| POST | `/suggest` `{diagnosisId}` | 返回建议数组（`ruleCode, assetType, targetKey, targetLabel, field, beforeValue, afterValue, reason, confidence, accepted`） |
| POST | `/task` `{diagnosisId,changes[]}` | 创建 → `DRAFT`，任务号 `TN{yyyyMMdd}-{seq}` |
| GET | `/task/page` · `/task/{id}` · `/task/{id}/impact` | 详情含 `changes, snapshots, audit, report, diagnosis, verifySummary, onlineRecheck, observeAlert, effectiveConfig` |
| PUT | `/task/{id}/changes` `{changes,verifyConfig?}` | 仅 DRAFT/VERIFIED；VERIFIED 下修改 → 回 DRAFT 且 `round+1` |
| POST | `/task/{id}/execute` | 仅 DRAFT；无采纳变更 400；**同资产被其他进行中任务占用 → 409**；快照 + 应用草稿 → APPLIED → 自动开始验证 VERIFYING →（异步）VERIFIED |
| POST | `/task/{id}/verify` · `/verify/abort` | 重跑 / 中止 |
| GET | `/task/{id}/report` · `/verify/case/{caseId}` | 报告 `{report{conclusion,originFixed,…},cases[]}`、用例前后详情 |
| POST | `/task/{id}/submit-approval` `{note?}` | 仅 VERIFIED；结论 FAIL 拒绝；PASS_WITH_WARN 必须填 note → PENDING_APPROVAL + 通知 |
| POST | `/task/{id}/withdraw` | 仅 PENDING_APPROVAL → VERIFIED |
| POST | `/task/{id}/approve` `{approved,comment}` | **超管**；仅 PENDING_APPROVAL；驳回 → VERIFIED；通过 → 发布 → PUBLISHED（写资产、recall 同步、回归沉淀、线上复验、observeUntil=+7d） |
| GET | `/approval/pending` | 待审批列表 |
| POST | `/task/{id}/rollback` `{reason,force?}` | **超管**；PUBLISHED 必填 reason；冲突时 `PARTIAL`，`force=true` 强制 → ROLLED_BACK；已回滚不可再回滚 |
| POST | `/task/{id}/cancel` | 仅 DRAFT/APPLIED/VERIFIED → CANCELLED |
| GET/PUT | `/config?scope=GLOBAL\|AGENT&aiBodyCode=` · `/config/all` | 验证集配置；PUT **超管**；`maxCases` 1–200 |
| POST | `/prompt/quick-verify` `{taskId,groupName,content,question?}` | 内容非空；创建临时版本 → analyze → `{ok,version,elapsedMs,result}` |
| GET | `/prompt/editor-context?taskId=&groupName=` | `{group,current{version,content},question,rootCause,errorStepId,anchors[],failedSteps[]}` |
| GET/POST/DELETE/PUT | `/regression/page` · `/regression` · `/regression/{id}` · `/regression/{id}/toggle` | 回归用例 |
| GET/PUT | `/notifications` · `/notifications/{id}/read` | 通知 |
| POST | `/observe/check` | 手动触发观察期巡检 |

### 4.3 任务状态机
```
DRAFT ──execute──▶ APPLIED ──(auto)──▶ VERIFYING ──▶ VERIFIED ──submit──▶ PENDING_APPROVAL ──approve──▶ PUBLISHED ──rollback──▶ ROLLED_BACK
  ▲                                                     │  ▲                    │                           │
  └──────────── updateChanges (round+1) ────────────────┘  └──── withdraw/reject ┘                           └─(观察期 7d, 24h 同类👎≥2 告警)
DRAFT / APPLIED / VERIFIED ──cancel──▶ CANCELLED
```

### 4.4 建议规则（SuggestionEngine）
R1 应选表未召回→补表描述关键词 · R2 已召回未选→增区分性描述 · R3 应选表未绑智能体→绑表 · R4/R5 指标/维度未映射→实体别名 · R6 维度值不匹配→枚举值 · R7 问题词未映射列→实体别名 · R8 聚合方式错→AGG_TYPE · R9 拆分错误→拆解提示词(低置信) · R10 业务口径→业务知识 · R11 总结错误→总结提示词 · R12 结果错误→业务知识。种子数据下：C1 选「指标/维度提取错误」→ R7；C2 选「拆分错误」→ R9。

---

## 5. 种子数据说明（seed_minimal.sql）

| 对象 | 内容 | 设计意图 |
|---|---|---|
| 智能体 | `xs_agent` 线损智能体 | 唯一智能体 |
| 表 | `view_by_voltage`(11, 分压线损率) · `view_fenqu`(12, 分区线损率) | 智能体**只绑了 view_fenqu**，应选表应为 view_by_voltage → 可触发 R3 |
| 指标/维度 | `ll_rate` 线损率(别名 线损率) · `voltage_level` 电压等级(别名 voltagelevel) | 问题中的「电压等级」未映射 → R7 建议追加别名 |
| 问答 | `C1`、`C2`（同问题「查询深圳各电压等级的分压线损率」，用了 view_fenqu，`unmatched_columns=["voltagelevel"]`） | C1 autoHint=METRIC_DIM，C2 autoHint=DECOMPOSE |
| 反馈 | C1 👎(TABLE_SELECT, METRIC_DIM) by admin · C2 👎(TABLE_SELECT) by yangbin | 待处理状态 |

重置到起点：重新执行 `seed_minimal.sql` 前先 `TRUNCATE` 诊断/任务类表（可参考 `docs/dev/reset_demo_data.sh`）。

---

## 6. 验证方式

### 6.1 单元测试（无需任何服务）
```bash
cd backend/bi-manager && mvn test -Dtest=SuggestionEngineTest,VerifyJudgeTest   # 11 passed
cd algorithm/analyze-streaming && python3 -m pytest tests/test_tuning_support.py -q  # 13 passed
```

### 6.2 API 功能测试（bi-manager + mock + DB）
```bash
python3 handover/tests/func_test_quality.py http://127.0.0.1:8591      # 直连，自动签 JWT
# 或经前端代理： python3 handover/tests/func_test_quality.py http://127.0.0.1:8600
```
覆盖：认证 → 反馈（覆盖写 / 校验 / 👍 回归沉淀 / 状态流转）→ 诊断（列表 autoHint / trace 5 环节 / 日志 / 保存定位联动反馈）→ 建议 R7 → 任务（DRAFT 守卫 / 并发 409 / 异步 VERIFIED / 报告 PASS）→ 审批与权限（提交 / 待办 / 通知 / 非超管 403 / 撤回 / 驳回 / 通过发布 / 线上复验 / 观察期）→ 回滚（403 / 必填原因 / 成功 / 幂等守卫 / 审计完整）→ 二轮编辑 round+1 → 提示词编辑器（上下文 / lint / 快速验证 / 空内容 400）→ 回归集 CRUD → 配置（默认值 / 超限 400 / 非超管 403 / AGENT 覆盖）→ 通知 / 观察期巡检。
每次运行前请把数据重置到起点（§5），输出 `handover/tests/FUNC_TEST_REPORT.md`。

### 6.3 UI E2E（需构建前端并启动 fe_static_proxy）
```bash
python3 handover/tests/e2e_login.py            http://127.0.0.1:8600   # 登录 → 管理端 → 问答质量管理
python3 handover/tests/e2e_quality_ui.py       http://127.0.0.1:8600   # 13 步主流程（反馈→诊断→建议→任务→验证→审批→发布→各 Tab）+ 截图
python3 handover/tests/e2e_quality_ui_prompt.py                       # 👎 弹窗提交 + R9 → 提示词编辑器 → 草稿版本
python3 handover/tests/e2e_quality_ui_prompt.py c                     # 提示词任务 执行并验证
```
参考截图：`docs/prototype/screenshots/impl/00a–17_*.png`。

### 6.4 手工验收路径
见 `docs/dev/SANDBOX_ACCEPTANCE.md`（A 应用端反馈 → B 诊断 → C 任务/审批/发布/回滚 → D 提示词编辑器 → E 其它 Tab）。

---

## 7. 已知状态与注意事项

1. **已修复的竞态（供了解）**：`execute()` 曾在事务内直接启动异步验证线程；下游响应很快（如 mock）时，线程先把任务写成 `VERIFIED`，随后事务提交用过期的 `VERIFYING` 覆盖，任务卡死。现改为 `TransactionSynchronization.afterCommit` 后再启动（`TuningServiceImpl.startVerify`）。`func_test_quality.py` 连续 3 次 64/64 通过。
2. 发布到 `AGENT_TABLE` / `KNOWLEDGE` 需要 `dcar_ai_body_relation.created_by/updated_by` 非空，`AssetApplier` 已显式赋值 0；若库表结构与种子不同请对照 §1.1 实体字段。
3. 所有 JSON 列实体字段为 `String`，通过 `ChatQualityServiceImpl.parseJson/parseArr` 解析；对外输出已在 service 层展开为数组/对象。
4. 日期统一 `yyyy-MM-dd HH:mm:ss`（`com.quality.util.Jsons`）；`LocalDateTime` 不能直接 `JSON.toJSON`。
5. 前端 production 构建内存 >2 GB；低内存环境请用 `--mode development`。
6. `application-sandbox.yml` 与 `db/dev_minimal.sql` 仅用于隔离环境；生产用 `application.yml` + nacos。
7. recall `/api/metadata/sync` 与 chat-server `/metadata/agent` 失败时：前者仅记录告警；后者回退为空 meta（验证仍可跑，但建议 mock 返回完整 meta）。

---

## 8. 快速核对清单（接手后 10 分钟自检）

- [ ] `mvn -q -DskipTests package` 成功；`mvn test -Dtest=SuggestionEngineTest,VerifyJudgeTest` 11 通过
- [ ] `pytest tests/test_tuning_support.py` 13 通过
- [ ] `bootstrap.sh` 后 `GET /api/v1/chat/error-types` 返回 8 条
- [ ] `GET /api/v1/chat/diagnosis/page?quick=feedback` 返回 C1/C2，autoHint 分别 METRIC_DIM / DECOMPOSE
- [ ] `func_test_quality.py` 64/64 PASS
- [ ] （可选）UI：登录 admin → 管理端 → 问答质量管理 7 个 Tab 可见；tester 登录看不到「管理端」
