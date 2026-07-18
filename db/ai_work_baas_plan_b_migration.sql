-- Plan B 迁移脚本(spec §6.1):对已部署的 Plan A 元数据库执行一次。
-- 新装环境直接用 db/ai_work_baas.sql,不执行本脚本。
USE `ai_work_baas`;

ALTER TABLE `baas_project`
  ADD COLUMN `ddl_fence_epoch` bigint NOT NULL DEFAULT 0 COMMENT '项目级单调 fencing 计数(spec §9.2)' AFTER `runtime_db_password_cipher`,
  MODIFY COLUMN `status` varchar(16) NOT NULL COMMENT 'PROVISIONING/ACTIVE/MIGRATING/FAILED/DELETING/DELETED';

ALTER TABLE `baas_ddl_log`
  MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT,
  MODIFY COLUMN `project_id` bigint NOT NULL,
  MODIFY COLUMN `retry_count` int NOT NULL DEFAULT 0,
  MODIFY COLUMN `ddl_text` text NULL COMMENT '脱敏 DDL(默认值字面量以 ? 占位);纯元数据操作为 NULL',
  MODIFY COLUMN `status` varchar(16) NOT NULL COMMENT 'PENDING/RUNNING/SUCCESS/FAILED',
  ADD COLUMN `operation_type` varchar(16) NOT NULL DEFAULT 'create' COMMENT 'create/alter/drop/acl-config/cleanup-drop/reconcile' AFTER `project_id`,
  ADD COLUMN `table_name` varchar(64) DEFAULT NULL COMMENT '目标表名(项目级操作为 NULL)' AFTER `operation_type`,
  ADD COLUMN `table_id` bigint DEFAULT NULL COMMENT '不可变目标表 ID(baas_table.id)' AFTER `table_name`,
  ADD COLUMN `request_hash` char(64) NOT NULL DEFAULT '' COMMENT '操作指纹 SHA-256 hex(spec §9.2)' AFTER `table_id`,
  ADD COLUMN `result_snapshot` mediumtext DEFAULT NULL COMMENT '成功结果 JSON,幂等重放返回' AFTER `request_hash`,
  ADD COLUMN `owner_token` varchar(64) DEFAULT NULL COMMENT '本次执行唯一标识;PENDING 为 NULL' AFTER `result_snapshot`,
  ADD COLUMN `fence_epoch` bigint DEFAULT NULL COMMENT '取得所有权时的项目 epoch;历史/PENDING 为 NULL' AFTER `owner_token`,
  ADD COLUMN `trigger_source` varchar(16) DEFAULT NULL COMMENT '仅 reconcile:MANUAL/SCHEDULED' AFTER `fence_epoch`,
  ADD COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER `create_time`,
  DROP KEY `uk_operation`,
  ADD UNIQUE KEY `uk_project_operation` (`project_id`, `operation_id`),
  ADD KEY `idx_status_update` (`status`, `update_time`);

-- Plan A ddl_text/error_msg 可能含原始 SQL/JDBC message;无法可靠解析,统一脱敏为稳定历史码。
UPDATE `baas_ddl_log`
SET `ddl_text` = 'LEGACY_DDL_REDACTED',
    `error_msg` = CASE WHEN `status` = 'FAILED' THEN 'LEGACY_FAILURE_REDACTED' ELSE NULL END
WHERE `request_hash` = '';

-- Plan A RUNNING 记录没有 request body/owner_token/fence_epoch,无法按 Plan B 探测式协议安全接管。
UPDATE `baas_ddl_log`
SET `status` = 'FAILED', `error_msg` = 'LEGACY_RUNNING_NOT_RESUMABLE'
WHERE `status` = 'RUNNING' AND `owner_token` IS NULL AND `fence_epoch` IS NULL;

-- ADD COLUMN 为回填历史行临时使用默认值;移除后与全量脚本终态完全一致。
ALTER TABLE `baas_ddl_log`
  ALTER COLUMN `operation_type` DROP DEFAULT,
  ALTER COLUMN `request_hash` DROP DEFAULT;

ALTER TABLE `baas_table`
  MODIFY COLUMN `comment` varchar(2048) DEFAULT NULL,
  MODIFY COLUMN `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'CREATING/ACTIVE/ALTERING/FAILED/CONFLICT/DELETED';

ALTER TABLE `baas_column`
  MODIFY COLUMN `comment` varchar(1024) DEFAULT NULL,
  MODIFY COLUMN `default_value` text DEFAULT NULL COMMENT '规范化默认值(true/false、数值、字符串原文或 CURRENT_TIMESTAMP)';
