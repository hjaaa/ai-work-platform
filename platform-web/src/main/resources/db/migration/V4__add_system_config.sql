-- 系统配置表，存储可通过前端修改的运行时配置
CREATE TABLE system_config (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_key  VARCHAR(128) NOT NULL COMMENT '配置键',
    config_value TEXT         COMMENT '配置值',
    description VARCHAR(256) COMMENT '配置说明',
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_config_key (config_key)
) COMMENT '系统配置表';

-- 初始化默认配置
INSERT INTO system_config (config_key, config_value, description) VALUES
    ('workspace_base_path', '/tmp/workspace/projects', '项目工作目录'),
    ('claude_cli_path', '/usr/local/bin/claude', 'Claude Code CLI 路径');
