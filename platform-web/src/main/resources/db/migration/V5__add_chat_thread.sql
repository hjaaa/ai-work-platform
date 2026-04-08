-- 对话线程表：将扁平的 project → message 模型重构为 project → thread → message 三层结构

-- 1. 创建 chat_thread 表
CREATE TABLE chat_thread (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    thread_id VARCHAR(64) NOT NULL UNIQUE COMMENT '线程业务标识',
    project_id VARCHAR(64) NOT NULL COMMENT '所属项目',
    title VARCHAR(256) DEFAULT '新对话' COMMENT '线程标题',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    INDEX idx_project_updated (project_id, updated_at DESC)
) COMMENT '对话线程表';

-- 2. conversation 表新增 thread_id（先允许 NULL，迁完数据再加 NOT NULL）
ALTER TABLE conversation ADD COLUMN thread_id VARCHAR(64) NULL COMMENT '所属线程' AFTER project_id;
ALTER TABLE conversation ADD INDEX idx_thread_id (thread_id);

-- 3. 为每个已有项目创建默认线程（用 legacy_ 前缀区分）
INSERT INTO chat_thread (thread_id, project_id, title, created_at, updated_at)
SELECT
    CONCAT('legacy_', project_id) AS thread_id,
    project_id,
    '历史对话' AS title,
    MIN(created_at) AS created_at,
    MAX(created_at) AS updated_at
FROM conversation
WHERE deleted = 0
GROUP BY project_id;

-- 4. 将旧 conversation 记录关联到对应的默认线程
UPDATE conversation c
JOIN chat_thread t ON c.project_id = t.project_id AND t.thread_id = CONCAT('legacy_', c.project_id)
SET c.thread_id = t.thread_id
WHERE c.thread_id IS NULL;

-- 5. thread_id 设为 NOT NULL（此时所有旧数据已有值）
ALTER TABLE conversation MODIFY thread_id VARCHAR(64) NOT NULL COMMENT '所属线程';
