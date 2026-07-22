-- Plan D 可恢复迁移脚本(spec §9.1 v3 发布协议②):可重复执行。
-- 不含 USE:Cloud 形态对 ai_work_baas 执行,Boot 并库形态对 ai_work 执行同一脚本,
-- 目标库由 mysql 客户端调用时指定(如 mysql ai_work_baas < 本文件)。
-- 新装环境直接使用 db/ai_work_baas.sql(或 boot 的 db/ai_work.sql),不执行本脚本。

DELIMITER $$
DROP PROCEDURE IF EXISTS `migrate_baas_plan_d`$$
CREATE PROCEDURE `migrate_baas_plan_d`()
BEGIN
  DECLARE column_exists int DEFAULT 0;

  SELECT COUNT(*) INTO column_exists FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'baas_project'
     AND COLUMN_NAME = 'system_table_version';
  IF column_exists = 0 THEN
    ALTER TABLE `baas_project`
      ADD COLUMN `system_table_version` int NOT NULL DEFAULT 0
        COMMENT '已确认的系统表 manifest 版本,0=未确认(spec §9.1)'
        AFTER `ddl_fence_epoch`;
  END IF;
END$$
CALL `migrate_baas_plan_d`()$$
DROP PROCEDURE IF EXISTS `migrate_baas_plan_d`$$
DELIMITER ;
