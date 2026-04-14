-- 补全所有表的基类公共字段：created_by、updated_at、deleted

-- ========== conversation 表 ==========
-- 缺少: created_by, updated_at
ALTER TABLE conversation ADD COLUMN created_by VARCHAR(64) COMMENT '创建人' AFTER message_type;
ALTER TABLE conversation ADD COLUMN updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间' AFTER created_at;

-- ========== generation 表 ==========
-- 缺少: created_by, updated_at
ALTER TABLE generation ADD COLUMN created_by VARCHAR(64) COMMENT '创建人' AFTER error_message;
ALTER TABLE generation ADD COLUMN updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间' AFTER created_at;

-- ========== deployment 表 ==========
-- 缺少: created_by, updated_at
ALTER TABLE deployment ADD COLUMN created_by VARCHAR(64) COMMENT '创建人' AFTER error_message;
ALTER TABLE deployment ADD COLUMN updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间' AFTER created_at;

-- ========== system_config 表 ==========
-- 缺少: created_by, deleted
ALTER TABLE system_config ADD COLUMN created_by VARCHAR(64) COMMENT '创建人' AFTER description;
ALTER TABLE system_config ADD COLUMN deleted TINYINT DEFAULT 0 COMMENT '逻辑删除: 0-未删除, 1-已删除' AFTER updated_at;

-- ========== chat_thread 表 ==========
-- 缺少: created_by
ALTER TABLE chat_thread ADD COLUMN created_by VARCHAR(64) COMMENT '创建人' AFTER claude_session_id;
