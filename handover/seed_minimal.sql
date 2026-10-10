-- =====================================================================
-- 问答质量管理 / 自动调优 — 独立测试最小种子数据
-- 用途：在不部署整个平台的前提下，为 bi-manager 的 com.quality 模块提供
--       其依赖的上游业务表（智能体 / 表 / 指标维度）与一组演示问答数据。
-- 前置：先执行 backend/bi-manager/src/main/resources/db/quality.sql
-- 注意：请使用 utf8mb4 连接执行（mysql --default-character-set=utf8mb4）
-- =====================================================================

SET NAMES utf8mb4;

-- ---------- 1. 上游业务表（平台原有表的最小子集，仅建缺失的） ----------
CREATE TABLE IF NOT EXISTS `dcar_ai_body` (
  `id` INT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code` VARCHAR(255), `name` VARCHAR(255), `interaction_mode` INT, `theme_code` VARCHAR(255),
  `authorize_strategy` INT DEFAULT 0, `created_by` BIGINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, `updated_by` BIGINT NOT NULL DEFAULT 1,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `hot_words` LONGTEXT, `tenant_id` BIGINT, `description` VARCHAR(1000),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体';

CREATE TABLE IF NOT EXISTS `dcar_ai_body_relation` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code` VARCHAR(255), `relation_id` BIGINT, `relation_type` INT,
  `created_by` BIGINT NOT NULL DEFAULT 1, `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_by` BIGINT NOT NULL DEFAULT 1, `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `tenant_id` BIGINT DEFAULT 1,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体绑定表';

CREATE TABLE IF NOT EXISTS `olap_table_pro` (
  `id` BIGINT NOT NULL AUTO_INCREMENT, `meta_table_id` BIGINT, `db_name` VARCHAR(100), `tb_name` VARCHAR(100),
  `cn_name` VARCHAR(50), `tb_cn_name` VARCHAR(100), `note` VARCHAR(100), `theme` VARCHAR(100), `theme_key` VARCHAR(100),
  `type` VARCHAR(100), `type_key` VARCHAR(100), `model_type` SMALLINT DEFAULT 0, `tb_type` VARCHAR(100) DEFAULT '0',
  `tb_type_key` VARCHAR(100) DEFAULT '0', `status` SMALLINT NOT NULL DEFAULT 3,
  `created_by` VARCHAR(128) NOT NULL DEFAULT '1', `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_by` VARCHAR(128) NOT NULL DEFAULT '1', `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `source_id` BIGINT, `query_sql` LONGTEXT, `view_sql` LONGTEXT, `tenant_id` BIGINT DEFAULT 1,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分析数据表';

CREATE TABLE IF NOT EXISTS `olap_basic_pro` (
  `id` BIGINT NOT NULL AUTO_INCREMENT, `tenant_id` BIGINT DEFAULT 1, `category` TINYINT NOT NULL DEFAULT 1,
  `key_str` VARCHAR(255), `chinese_name` VARCHAR(255), `standard_name` VARCHAR(255), `alias` VARCHAR(255),
  `english_name` VARCHAR(255), `abbreviation` TEXT, `classification_label` VARCHAR(255), `classification_label_name` VARCHAR(255),
  `olap_label` VARCHAR(255) DEFAULT 'olapcommon', `olap_label_name` VARCHAR(255) DEFAULT '通用', `data_type` VARCHAR(500),
  `data_source_type` TINYINT DEFAULT 1, `status` TINYINT NOT NULL DEFAULT 2, `principal` BIGINT, `principal_name` VARCHAR(50),
  `principal_email` VARCHAR(50), `approver` BIGINT, `approver_name` VARCHAR(50), `approver_email` VARCHAR(50),
  `is_show` VARCHAR(2) DEFAULT '1', `created_by` VARCHAR(256), `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `updated_by` VARCHAR(256), `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指标维度基础信息表（category 1 维度 / 2 指标）';

CREATE TABLE IF NOT EXISTS `olap_table_field_mapping` (
  `id` BIGINT NOT NULL AUTO_INCREMENT, `table_id` BIGINT, `field_key` VARCHAR(255), `field_name` VARCHAR(255),
  `basic_id` BIGINT, `basic_key` VARCHAR(255), `basic_type` VARCHAR(100), `basic_type_key` VARCHAR(100), `basic_name` VARCHAR(100),
  `field_type` VARCHAR(100), `field_type_name` VARCHAR(100), `field_note` VARCHAR(500), `is_customize` INT DEFAULT 0,
  `pf_status` SMALLINT DEFAULT 0, `summary` VARCHAR(100), `summary_key` VARCHAR(100), `expression` VARCHAR(500),
  `inner_field_key` VARCHAR(255), `look_back_mapping_ids` LONGTEXT, `look_back_mapping_names` LONGTEXT,
  `look_back_flag` INT NOT NULL DEFAULT 1, `height` INT NOT NULL DEFAULT 1000, `tenant_id` BIGINT DEFAULT 1,
  `created_by` VARCHAR(128) DEFAULT '1', `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `updated_by` VARCHAR(128) DEFAULT '1', `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='表字段 ↔ 指标维度映射';

CREATE TABLE IF NOT EXISTS `dcar_chat_model_qa` (
  `id` BIGINT NOT NULL AUTO_INCREMENT, `chat_session_id` VARCHAR(64), `chat_id` VARCHAR(64), `item_id` INT DEFAULT 0,
  `type` VARCHAR(32), `question` TEXT, `status` INT DEFAULT 1, `feedback` INT, `reason` VARCHAR(500),
  `minio_file_path` VARCHAR(512), `record_id` BIGINT, `history_data_minio_path` VARCHAR(512), `tenant_id` BIGINT,
  `created_by` BIGINT, `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP, `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='问答记录（反馈镜像用）';

CREATE TABLE IF NOT EXISTS `dcar_chat_record` (
  `id` BIGINT NOT NULL AUTO_INCREMENT, `chat_session_id` VARCHAR(64), `ai_body_code` VARCHAR(64), `chat_name` VARCHAR(512),
  `tenant_id` BIGINT, `created_by` BIGINT, `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP, `updated_by` BIGINT,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, `user_id` BIGINT, `status` INT,
  `minio_file_path` VARCHAR(512),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话记录';

CREATE TABLE IF NOT EXISTS `olap_dim_value` (
  `id` BIGINT NOT NULL AUTO_INCREMENT, `basic_id` BIGINT, `dim_value` VARCHAR(255), `dim_label` VARCHAR(255),
  `tenant_id` BIGINT DEFAULT 1, PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='维度枚举值';

-- ---------- 2. 业务种子：1 个智能体 · 2 张表 · 1 指标 + 1 维度 ----------
INSERT IGNORE INTO `dcar_ai_body` (`id`,`code`,`name`,`interaction_mode`,`tenant_id`,`description`,`created_by`,`updated_by`)
VALUES (7,'xs_agent','线损智能体',1,1,'电网线损分析智能体',1,1);

INSERT IGNORE INTO `olap_table_pro` (`id`,`tb_name`,`cn_name`,`note`,`status`,`created_by`,`updated_by`,`tenant_id`)
VALUES (11,'view_by_voltage','分压线损率','线损率按电压等级统计',3,'1','1',1),
       (12,'view_fenqu','分区线损率','分区线损率',3,'1','1',1);

-- 智能体默认只绑定了 view_fenqu（故意不绑 view_by_voltage，用于触发规则 R3 "应选表未绑定到智能体"）
INSERT IGNORE INTO `dcar_ai_body_relation` (`id`,`code`,`relation_id`,`relation_type`,`created_by`,`updated_by`,`tenant_id`)
VALUES (2,'xs_agent',12,0,1,1,1);

INSERT IGNORE INTO `olap_basic_pro` (`id`,`category`,`chinese_name`,`alias`,`english_name`,`status`,`tenant_id`)
VALUES (101,2,'分压线损率','线损率','ll_rate',2,1),
       (102,1,'电压等级','voltagelevel','voltage_level',2,1);

INSERT IGNORE INTO `olap_table_field_mapping` (`id`,`table_id`,`field_key`,`field_name`,`basic_id`,`basic_key`,`basic_type_key`,`basic_name`,`summary_key`)
VALUES (1001,11,'voltage_level','电压等级',102,'voltage_level','dim','电压等级',NULL),
       (1002,11,'ll_rate','线损率',101,'ll_rate','index','分压线损率','avg'),
       (1003,12,'ll_rate','线损率',101,'ll_rate','index','分区线损率','avg');

-- ---------- 3. 演示问答：2 轮已完成问答 + 2 条 👎 反馈 ----------
INSERT IGNORE INTO `dcar_chat_record` (`id`,`chat_session_id`,`ai_body_code`,`chat_name`,`tenant_id`,`created_by`,`user_id`,`status`)
VALUES (1,'S1','xs_agent','查询深圳各电压等级的分压线损率',1,1,1,1);

INSERT IGNORE INTO `dcar_chat_model_qa` (`id`,`chat_session_id`,`chat_id`,`item_id`,`type`,`question`,`tenant_id`,`created_by`)
VALUES (1,'S1','C1',0,'query','查询深圳各电压等级的分压线损率',1,1),
       (2,'S1','C2',0,'query','查询深圳各电压等级的分压线损率',1,1);

INSERT IGNORE INTO `chat_analysis_trace`
 (`id`,`chat_session_id`,`chat_id`,`ai_body_code`,`tenant_id`,`user_id`,`username`,`question`,`recall_enabled`,`meta_table_count`,
  `decomposition_steps`,`used_tables`,`resolved_metrics`,`resolved_dims`,`final_answer`,`status`,`total_elapsed_ms`,`prompt_version`,`auto_error_hint`)
VALUES
 (1,'S1','C1','xs_agent',1,1,'admin','查询深圳各电压等级的分压线损率',0,1,
  '[{"step_id":"step_1","step_type":"query","params":{"expected_table":"view_fenqu"}}]','["view_fenqu"]','["ll_rate"]','[]',
  '深圳分区线损率为 3.2%','success',1800,'v1.0.5','METRIC_DIM'),
 (2,'S1','C2','xs_agent',1,1,'admin','查询深圳各电压等级的分压线损率',0,1,
  '[{"step_id":"step_1","step_type":"query","params":{"expected_table":"view_fenqu"}}]','["view_fenqu"]','["ll_rate"]','[]',
  '深圳分区线损率为 3.2%','success',1750,'v1.0.5','DECOMPOSE');

INSERT IGNORE INTO `chat_step_trace`
 (`id`,`chat_id`,`step_id`,`query_description`,`expected_table`,`resolved_table_id`,`resolved_table_name`,`unmatched_columns`,`row_count`,`elapsed_ms`)
VALUES (1,'C1','step_1','查询深圳各电压等级线损率','view_fenqu',12,'view_fenqu','["voltagelevel"]',5,120),
       (2,'C2','step_1','查询深圳各电压等级线损率','view_fenqu',12,'view_fenqu','["voltagelevel"]',5,118);

INSERT IGNORE INTO `chat_feedback`
 (`id`,`chat_session_id`,`chat_id`,`ai_body_code`,`tenant_id`,`user_id`,`username`,`question`,`answer_snapshot`,`rating`,`error_types`,`description`,`status`)
VALUES (1,'S1','C1','xs_agent',1,1,'admin','查询深圳各电压等级的分压线损率','深圳分区线损率为 3.2%',-1,'["TABLE_SELECT","METRIC_DIM"]','应该用分压表，电压等级没有拆开',0),
       (2,'S1','C2','xs_agent',1,2,'yangbin','查询深圳各电压等级的分压线损率','深圳分区线损率为 3.2%',-1,'["TABLE_SELECT"]','结果没有按电压等级分组',0);
