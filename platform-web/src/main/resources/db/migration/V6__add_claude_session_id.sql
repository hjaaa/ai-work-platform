-- chat_thread 新增 Claude CLI 会话 ID，用于 --session-id / --resume 复用对话上下文
ALTER TABLE chat_thread ADD COLUMN claude_session_id VARCHAR(64) NULL COMMENT 'Claude CLI 会话 ID，首次对话时生成';
