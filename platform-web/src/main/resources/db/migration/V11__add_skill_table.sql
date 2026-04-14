-- Skill 表：存储平台管理的 Claude Code skill 信息
CREATE TABLE skill (
    id          BIGINT       NOT NULL COMMENT '主键（雪花算法）',
    skill_id    VARCHAR(64)  NOT NULL COMMENT '业务主键（全局唯一）',
    name        VARCHAR(100) NOT NULL COMMENT 'Skill 名称',
    description TEXT         NULL     COMMENT 'Skill 描述/内容（markdown 格式，写入磁盘文件）',
    scope       VARCHAR(20)  NOT NULL COMMENT '作用域：PERSONAL-个人级，PROJECT-项目级',
    project_id  VARCHAR(64)  NULL     COMMENT '关联项目 ID（scope=PROJECT 时必填）',
    created_by  VARCHAR(64)  NULL     COMMENT '创建人',
    created_at  DATETIME     NOT NULL COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL COMMENT '更新时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_skill_id (skill_id),
    KEY idx_scope_project (scope, project_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = 'Skill 信息表';
