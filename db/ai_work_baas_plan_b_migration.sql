-- Plan B 可恢复迁移脚本(spec §6.1):可从任意已提交阶段重复执行。
-- 新装环境直接使用 db/ai_work_baas.sql，不执行本脚本。
USE `ai_work_baas`;

DELIMITER $$
DROP PROCEDURE IF EXISTS `migrate_baas_plan_b`$$
CREATE PROCEDURE `migrate_baas_plan_b`()
BEGIN
  DECLARE column_exists int DEFAULT 0;
  DECLARE index_exists int DEFAULT 0;
  DECLARE invalid_count bigint DEFAULT 0;

  -- 所有缩放/DDL 前统一预检；支持从早期 partial signed 版本安全收敛到 unsigned。
  SELECT COUNT(*) INTO invalid_count FROM `baas_ddl_log`
   WHERE `id` < 0 OR `project_id` < 0 OR `retry_count` < 0;
  IF invalid_count > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'BAAS_PLAN_B_UNSIGNED_PREFLIGHT_FAILED';
  END IF;

  SELECT COUNT(*) INTO column_exists FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'baas_project' AND COLUMN_NAME = 'ddl_fence_epoch';
  IF column_exists = 0 THEN
    ALTER TABLE `baas_project` ADD COLUMN `ddl_fence_epoch` bigint unsigned NOT NULL DEFAULT 0
      COMMENT '项目级单调 fencing 计数(spec §9.2)' AFTER `runtime_db_password_cipher`;
  ELSE
    ALTER TABLE `baas_project` MODIFY COLUMN `ddl_fence_epoch` bigint unsigned NOT NULL DEFAULT 0
      COMMENT '项目级单调 fencing 计数(spec §9.2)';
  END IF;
  ALTER TABLE `baas_project` MODIFY COLUMN `status` varchar(16) NOT NULL
    COMMENT 'PROVISIONING/ACTIVE/MIGRATING/FAILED/DELETING/DELETED';

  ALTER TABLE `baas_ddl_log`
    MODIFY COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    MODIFY COLUMN `project_id` bigint unsigned NOT NULL,
    MODIFY COLUMN `retry_count` int unsigned NOT NULL DEFAULT 0,
    MODIFY COLUMN `ddl_text` text NULL COMMENT '脱敏 DDL(默认值字面量以 ? 占位);纯元数据操作为 NULL',
    MODIFY COLUMN `status` varchar(16) NOT NULL COMMENT 'PENDING/RUNNING/SUCCESS/FAILED';

  SELECT COUNT(*) INTO column_exists FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'baas_ddl_log' AND COLUMN_NAME = 'operation_type';
  IF column_exists = 0 THEN
    ALTER TABLE `baas_ddl_log` ADD COLUMN `operation_type` varchar(16) NOT NULL DEFAULT 'create'
      COMMENT 'create/alter/drop/acl-config/cleanup-drop/reconcile' AFTER `project_id`;
  END IF;
  SELECT COUNT(*) INTO column_exists FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'baas_ddl_log' AND COLUMN_NAME = 'table_name';
  IF column_exists = 0 THEN
    ALTER TABLE `baas_ddl_log` ADD COLUMN `table_name` varchar(64) DEFAULT NULL
      COMMENT '目标表名(项目级操作为 NULL)' AFTER `operation_type`;
  END IF;
  SELECT COUNT(*) INTO column_exists FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'baas_ddl_log' AND COLUMN_NAME = 'table_id';
  IF column_exists = 0 THEN
    ALTER TABLE `baas_ddl_log` ADD COLUMN `table_id` bigint unsigned DEFAULT NULL
      COMMENT '不可变目标表 ID(baas_table.id)' AFTER `table_name`;
  ELSE
    SELECT COUNT(*) INTO invalid_count FROM `baas_ddl_log` WHERE `table_id` < 0;
    IF invalid_count > 0 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'BAAS_PLAN_B_TABLE_ID_PREFLIGHT_FAILED';
    END IF;
    ALTER TABLE `baas_ddl_log` MODIFY COLUMN `table_id` bigint unsigned DEFAULT NULL
      COMMENT '不可变目标表 ID(baas_table.id)';
  END IF;
  SELECT COUNT(*) INTO column_exists FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'baas_ddl_log' AND COLUMN_NAME = 'request_hash';
  IF column_exists = 0 THEN
    ALTER TABLE `baas_ddl_log` ADD COLUMN `request_hash` char(64) NOT NULL DEFAULT ''
      COMMENT '操作指纹 SHA-256 hex(spec §9.2)' AFTER `table_id`;
  END IF;
  SELECT COUNT(*) INTO column_exists FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'baas_ddl_log' AND COLUMN_NAME = 'result_snapshot';
  IF column_exists = 0 THEN
    ALTER TABLE `baas_ddl_log` ADD COLUMN `result_snapshot` mediumtext DEFAULT NULL
      COMMENT '成功结果 JSON,幂等重放返回' AFTER `request_hash`;
  END IF;
  SELECT COUNT(*) INTO column_exists FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'baas_ddl_log' AND COLUMN_NAME = 'owner_token';
  IF column_exists = 0 THEN
    ALTER TABLE `baas_ddl_log` ADD COLUMN `owner_token` varchar(64) DEFAULT NULL
      COMMENT '本次执行唯一标识;PENDING 为 NULL' AFTER `result_snapshot`;
  END IF;
  SELECT COUNT(*) INTO column_exists FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'baas_ddl_log' AND COLUMN_NAME = 'fence_epoch';
  IF column_exists = 0 THEN
    ALTER TABLE `baas_ddl_log` ADD COLUMN `fence_epoch` bigint unsigned DEFAULT NULL
      COMMENT '取得所有权时的项目 epoch;历史/PENDING 为 NULL' AFTER `owner_token`;
  ELSE
    SELECT COUNT(*) INTO invalid_count FROM `baas_ddl_log` WHERE `fence_epoch` < 0;
    IF invalid_count > 0 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'BAAS_PLAN_B_FENCE_PREFLIGHT_FAILED';
    END IF;
    ALTER TABLE `baas_ddl_log` MODIFY COLUMN `fence_epoch` bigint unsigned DEFAULT NULL
      COMMENT '取得所有权时的项目 epoch;历史/PENDING 为 NULL';
  END IF;
  SELECT COUNT(*) INTO column_exists FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'baas_ddl_log' AND COLUMN_NAME = 'trigger_source';
  IF column_exists = 0 THEN
    ALTER TABLE `baas_ddl_log` ADD COLUMN `trigger_source` varchar(16) DEFAULT NULL
      COMMENT '仅 reconcile:MANUAL/SCHEDULED' AFTER `fence_epoch`;
  END IF;
  -- Plan A 已包含 update_time；只规范属性，不重复 ADD。
  SELECT COUNT(*) INTO column_exists FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'baas_ddl_log' AND COLUMN_NAME = 'update_time';
  IF column_exists = 0 THEN
    ALTER TABLE `baas_ddl_log` ADD COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP
      ON UPDATE CURRENT_TIMESTAMP AFTER `create_time`;
  ELSE
    ALTER TABLE `baas_ddl_log` MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP
      ON UPDATE CURRENT_TIMESTAMP;
  END IF;

  SELECT COUNT(*) INTO index_exists FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'baas_ddl_log' AND INDEX_NAME = 'uk_operation';
  IF index_exists > 0 THEN ALTER TABLE `baas_ddl_log` DROP INDEX `uk_operation`; END IF;
  SELECT COUNT(*) INTO index_exists FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'baas_ddl_log' AND INDEX_NAME = 'uk_project_operation';
  IF index_exists = 0 THEN
    ALTER TABLE `baas_ddl_log` ADD UNIQUE KEY `uk_project_operation` (`project_id`, `operation_id`);
  END IF;
  SELECT COUNT(*) INTO index_exists FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'baas_ddl_log' AND INDEX_NAME = 'idx_status_update';
  IF index_exists = 0 THEN
    ALTER TABLE `baas_ddl_log` ADD KEY `idx_status_update` (`status`, `update_time`);
  END IF;

  UPDATE `baas_ddl_log` SET `ddl_text` = 'LEGACY_DDL_REDACTED',
    `error_msg` = CASE WHEN `status` = 'FAILED' THEN 'LEGACY_FAILURE_REDACTED' ELSE NULL END
   WHERE `request_hash` = '';
  UPDATE `baas_ddl_log` SET `status` = 'FAILED', `error_msg` = 'LEGACY_RUNNING_NOT_RESUMABLE'
   WHERE `status` = 'RUNNING' AND `owner_token` IS NULL AND `fence_epoch` IS NULL;

  ALTER TABLE `baas_ddl_log`
    MODIFY COLUMN `operation_type` varchar(16) NOT NULL
      COMMENT 'create/alter/drop/acl-config/cleanup-drop/reconcile',
    MODIFY COLUMN `request_hash` char(64) NOT NULL COMMENT '操作指纹 SHA-256 hex(spec §9.2)';
  ALTER TABLE `baas_table`
    MODIFY COLUMN `comment` varchar(2048) DEFAULT NULL,
    MODIFY COLUMN `status` varchar(16) NOT NULL DEFAULT 'ACTIVE'
      COMMENT 'CREATING/ACTIVE/ALTERING/FAILED/CONFLICT/DELETED';
  ALTER TABLE `baas_column`
    MODIFY COLUMN `comment` varchar(1024) DEFAULT NULL,
    MODIFY COLUMN `default_value` text DEFAULT NULL
      COMMENT '规范化默认值(true/false、数值、字符串原文或 CURRENT_TIMESTAMP)';
END$$
CALL `migrate_baas_plan_b`()$$
DROP PROCEDURE `migrate_baas_plan_b`$$
DELIMITER ;
