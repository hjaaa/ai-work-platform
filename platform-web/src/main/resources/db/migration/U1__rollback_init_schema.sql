-- 回滚脚本：撤销 V1__init_schema.sql
-- ⚠️ 此脚本需手动执行，Flyway 社区版不支持自动 undo
-- ⚠️ 执行前请确认已备份数据

DROP TABLE IF EXISTS deployment;
DROP TABLE IF EXISTS generation;
DROP TABLE IF EXISTS conversation;
DROP TABLE IF EXISTS project;

-- 如需同时清理 Flyway 历史记录，取消下行注释：
-- DELETE FROM flyway_schema_history WHERE version = '1';
