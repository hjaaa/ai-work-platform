-- 基线迁移：初始化 4 张核心表
-- 已有数据的数据库首次启动时，Flyway 会以 baseline 模式跳过此脚本

-- 项目表
CREATE TABLE IF NOT EXISTS project (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id VARCHAR(64) UNIQUE NOT NULL COMMENT '项目标识',
    name VARCHAR(128) NOT NULL COMMENT '项目名称',
    description TEXT COMMENT '项目描述',
    workspace_path VARCHAR(512) NOT NULL COMMENT '工作区路径',
    status VARCHAR(32) DEFAULT 'creating' COMMENT '状态: creating/active/deploying/deployed/failed',
    deploy_url VARCHAR(512) COMMENT '部署后的访问地址',
    created_by VARCHAR(64) NOT NULL COMMENT '创建人',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除'
) COMMENT '项目表';

-- 对话表
CREATE TABLE IF NOT EXISTS conversation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id VARCHAR(64) NOT NULL COMMENT '项目标识',
    role VARCHAR(16) NOT NULL COMMENT '角色: user/assistant/system',
    content TEXT NOT NULL COMMENT '消息内容',
    message_type VARCHAR(16) DEFAULT 'text' COMMENT '消息类型: text/file/code/progress',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    INDEX idx_project_id (project_id)
) COMMENT '对话表';

-- 生成记录表
CREATE TABLE IF NOT EXISTS generation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id VARCHAR(64) NOT NULL COMMENT '项目标识',
    type VARCHAR(16) DEFAULT 'code' COMMENT '生成类型: code/prd',
    prompt TEXT NOT NULL COMMENT '发送给 Claude Code 的完整 prompt',
    generated_files TEXT COMMENT '生成的文件列表 (JSON)',
    prd_content TEXT COMMENT 'PRD 生成时的结构化内容 (JSON)',
    status VARCHAR(16) DEFAULT 'running' COMMENT '状态: running/success/failed',
    duration_ms INT COMMENT '耗时毫秒',
    error_message TEXT COMMENT '错误信息',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    INDEX idx_project_id (project_id)
) COMMENT '生成记录表';

-- 部署记录表
CREATE TABLE IF NOT EXISTS deployment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id VARCHAR(64) NOT NULL COMMENT '项目标识',
    image_tag VARCHAR(128) NOT NULL COMMENT 'Docker 镜像标签',
    target_server VARCHAR(256) NOT NULL COMMENT '目标服务器',
    status VARCHAR(32) DEFAULT 'building' COMMENT '状态: building/pushing/deploying/running/failed/rolled_back',
    deploy_url VARCHAR(512) COMMENT '访问地址',
    error_message TEXT COMMENT '错误信息',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    INDEX idx_project_id (project_id)
) COMMENT '部署记录表';
