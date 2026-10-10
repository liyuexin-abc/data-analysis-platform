# 需求代码文件清单（相对原产品基线 c9ceb58）

## A. 新增文件（81，纯需求代码，可整体移除不影响原产品）

- algorithm/analyze-streaming/system_b/utils/tuning_support.py
- algorithm/analyze-streaming/tests/test_tuning_support.py
- backend/bi-manager/src/main/java/com/quality/client/DownstreamClient.java
- backend/bi-manager/src/main/java/com/quality/client/QualityProperties.java
- backend/bi-manager/src/main/java/com/quality/controller/ChatQualityController.java
- backend/bi-manager/src/main/java/com/quality/controller/TuningController.java
- backend/bi-manager/src/main/java/com/quality/dto/ApproveDTO.java
- backend/bi-manager/src/main/java/com/quality/dto/ChangeDTO.java
- backend/bi-manager/src/main/java/com/quality/dto/DiagnosisSaveDTO.java
- backend/bi-manager/src/main/java/com/quality/dto/FeedbackSubmitDTO.java
- backend/bi-manager/src/main/java/com/quality/dto/PageQuery.java
- backend/bi-manager/src/main/java/com/quality/dto/QuickVerifyDTO.java
- backend/bi-manager/src/main/java/com/quality/dto/RegressionCaseDTO.java
- backend/bi-manager/src/main/java/com/quality/dto/RollbackDTO.java
- backend/bi-manager/src/main/java/com/quality/dto/TaskCreateDTO.java
- backend/bi-manager/src/main/java/com/quality/engine/AssetApplier.java
- backend/bi-manager/src/main/java/com/quality/engine/SuggestionEngine.java
- backend/bi-manager/src/main/java/com/quality/engine/VerifyJudge.java
- backend/bi-manager/src/main/java/com/quality/entity/AssetSnapshot.java
- backend/bi-manager/src/main/java/com/quality/entity/ChatAnalysisTrace.java
- backend/bi-manager/src/main/java/com/quality/entity/ChatErrorDiagnosis.java
- backend/bi-manager/src/main/java/com/quality/entity/ChatErrorTypeDict.java
- backend/bi-manager/src/main/java/com/quality/entity/ChatFeedback.java
- backend/bi-manager/src/main/java/com/quality/entity/ChatStepTrace.java
- backend/bi-manager/src/main/java/com/quality/entity/RegressionCase.java
- backend/bi-manager/src/main/java/com/quality/entity/TuningAuditLog.java
- backend/bi-manager/src/main/java/com/quality/entity/TuningChange.java
- backend/bi-manager/src/main/java/com/quality/entity/TuningNotification.java
- backend/bi-manager/src/main/java/com/quality/entity/TuningRule.java
- backend/bi-manager/src/main/java/com/quality/entity/TuningTask.java
- backend/bi-manager/src/main/java/com/quality/entity/TuningVerifyCase.java
- backend/bi-manager/src/main/java/com/quality/entity/TuningVerifyConfig.java
- backend/bi-manager/src/main/java/com/quality/entity/TuningVerifyReport.java
- backend/bi-manager/src/main/java/com/quality/mapper/AssetSnapshotMapper.java
- backend/bi-manager/src/main/java/com/quality/mapper/ChatAnalysisTraceMapper.java
- backend/bi-manager/src/main/java/com/quality/mapper/ChatErrorDiagnosisMapper.java
- backend/bi-manager/src/main/java/com/quality/mapper/ChatErrorTypeDictMapper.java
- backend/bi-manager/src/main/java/com/quality/mapper/ChatFeedbackMapper.java
- backend/bi-manager/src/main/java/com/quality/mapper/ChatStepTraceMapper.java
- backend/bi-manager/src/main/java/com/quality/mapper/RegressionCaseMapper.java
- backend/bi-manager/src/main/java/com/quality/mapper/TuningAuditLogMapper.java
- backend/bi-manager/src/main/java/com/quality/mapper/TuningChangeMapper.java
- backend/bi-manager/src/main/java/com/quality/mapper/TuningNotificationMapper.java
- backend/bi-manager/src/main/java/com/quality/mapper/TuningRuleMapper.java
- backend/bi-manager/src/main/java/com/quality/mapper/TuningTaskMapper.java
- backend/bi-manager/src/main/java/com/quality/mapper/TuningVerifyCaseMapper.java
- backend/bi-manager/src/main/java/com/quality/mapper/TuningVerifyConfigMapper.java
- backend/bi-manager/src/main/java/com/quality/mapper/TuningVerifyReportMapper.java
- backend/bi-manager/src/main/java/com/quality/service/ChatQualityService.java
- backend/bi-manager/src/main/java/com/quality/service/ObserveScheduler.java
- backend/bi-manager/src/main/java/com/quality/service/RegressionService.java
- backend/bi-manager/src/main/java/com/quality/service/TuningService.java
- backend/bi-manager/src/main/java/com/quality/service/TuningVerifyRunner.java
- backend/bi-manager/src/main/java/com/quality/service/impl/ChatQualityServiceImpl.java
- backend/bi-manager/src/main/java/com/quality/service/impl/RegressionServiceImpl.java
- backend/bi-manager/src/main/java/com/quality/service/impl/TuningServiceImpl.java
- backend/bi-manager/src/main/java/com/quality/util/Jsons.java
- backend/bi-manager/src/main/resources/db/dev_minimal.sql
- backend/bi-manager/src/main/resources/db/quality.sql
- backend/bi-manager/src/test/java/com/quality/engine/SuggestionEngineTest.java
- backend/bi-manager/src/test/java/com/quality/engine/VerifyJudgeTest.java
- backend/chat-server/src/main/java/com/chatbi/chat/entity/ChatAnalysisTrace.java
- backend/chat-server/src/main/java/com/chatbi/chat/entity/ChatStepTrace.java
- backend/chat-server/src/main/java/com/chatbi/chat/mapper/ChatAnalysisTraceMapper.java
- backend/chat-server/src/main/java/com/chatbi/chat/mapper/ChatStepTraceMapper.java
- backend/chat-server/src/main/java/com/chatbi/chat/service/ChatTraceService.java
- backend/chat-server/src/main/java/com/chatbi/chat/service/impl/ChatTraceServiceImpl.java
- frontend/chatbi-new/src/api/qualityManager/qualityAPI.js
- frontend/chatbi-new/src/views/newBI/feedbackBar/index.vue
- frontend/chatbi-new/src/views/qualityManager/components/ApprovalCenter.vue
- frontend/chatbi-new/src/views/qualityManager/components/DiagnosisDetail.vue
- frontend/chatbi-new/src/views/qualityManager/components/DiagnosisList.vue
- frontend/chatbi-new/src/views/qualityManager/components/FeedbackList.vue
- frontend/chatbi-new/src/views/qualityManager/components/PromptEditor.vue
- frontend/chatbi-new/src/views/qualityManager/components/QualitySettings.vue
- frontend/chatbi-new/src/views/qualityManager/components/QualityStats.vue
- frontend/chatbi-new/src/views/qualityManager/components/RegressionList.vue
- frontend/chatbi-new/src/views/qualityManager/components/SuggestionTable.vue
- frontend/chatbi-new/src/views/qualityManager/components/TuningDetail.vue
- frontend/chatbi-new/src/views/qualityManager/components/TuningList.vue
- frontend/chatbi-new/src/views/qualityManager/index.vue

## B. 修改的原产品文件（17，仅注入点；每处均有注释“问答质量管理”/“调优”）

| 文件 | +行 | -行 |
|---|---|---|
| algorithm/analyze-streaming/system_b/app.py | 223 | 17 |
| algorithm/analyze-streaming/system_b/execution/dag_engine.py | 6 | 0 |
| algorithm/analyze-streaming/system_b/utils/db.py | 39 | 4 |
| algorithm/analyze-streaming/system_b/utils/log_manager.py | 6 | 2 |
| algorithm/analyze-streaming/system_b/utils/prompt_version_manager.py | 18 | 0 |
| backend/bi-manager/src/main/java/com/BiApplication.java | 1 | 1 |
| backend/bi-manager/src/main/resources/application.yml | 10 | 1 |
| backend/chat-server/src/main/java/com/chatbi/chat/controller/ChatQueryController.java | 12 | 1 |
| backend/chat-server/src/main/java/com/chatbi/chat/controller/MetadataController.java | 17 | 0 |
| backend/chat-server/src/main/java/com/chatbi/chat/controller/MetricQueryController.java | 45 | 0 |
| backend/chat-server/src/main/java/com/chatbi/chat/feign/client/ResolverClient.java | 3 | 2 |
| backend/chat-server/src/main/java/com/chatbi/chat/service/MetadataService.java | 3 | 0 |
| backend/chat-server/src/main/java/com/chatbi/chat/service/impl/MetadataServiceImpl.java | 17 | 0 |
| frontend/chatbi-new/src/components/LeftMenu/index.vue | 1 | 0 |
| frontend/chatbi-new/src/utils/common.js | 1 | 0 |
| frontend/chatbi-new/src/views/index.vue | 1 | 0 |
| frontend/chatbi-new/src/views/newBI/queryPage/index.vue | 21 | 0 |

## C. 非代码（需求 / 文档 / 交接）

- M  README.md
- A  docs/dev/README.md
- A  docs/dev/SANDBOX_ACCEPTANCE.md
- A  docs/dev/e2e_login.py
- A  docs/dev/e2e_quality_ui.py
- A  docs/dev/e2e_quality_ui_prompt.py
- A  docs/dev/fe_static_proxy.py
- A  docs/dev/func_test_quality.py
- A  docs/dev/mock_system_b.py
- A  docs/dev/reset_demo_data.sh
- A  docs/dev/sandbox_up.sh
- A  docs/prototype/app.js
- A  docs/prototype/index.html
- A  docs/prototype/mock.js
- A  docs/prototype/prototype.css
- A  docs/prototype/vendor/element-ui.css
- A  docs/prototype/vendor/element-ui.js
- A  docs/prototype/vendor/fonts/element-icons.ttf
- A  docs/prototype/vendor/fonts/element-icons.woff
- A  docs/prototype/vendor/vue.js
- A  handover/HANDOVER.md
- A  handover/bootstrap.sh
- A  handover/mock/fe_static_proxy.py
- A  handover/mock/gen_token.py
- A  handover/mock/mock_system_b.py
- A  handover/seed_minimal.sql
- A  handover/tests/FUNC_TEST_REPORT.md
- A  handover/tests/e2e_login.py
- A  handover/tests/e2e_quality_ui.py
- A  handover/tests/e2e_quality_ui_prompt.py
- A  handover/tests/func_test_quality.py
- A  "requirements/01-\351\227\256\347\255\224\350\264\250\351\207\217\347\256\241\347\220\206\346\226\271\346\241\210.md"
- A  "requirements/02-\345\216\237\345\236\213\350\257\264\346\230\216.md"
- A  "requirements/03-\350\207\252\345\212\250\350\260\203\344\274\230\350\256\276\350\256\241.md"
- A  requirements/README.md
- docs/prototype/screenshots/impl/*.png（实现截图 20 张）
