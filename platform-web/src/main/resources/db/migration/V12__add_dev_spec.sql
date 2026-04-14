-- 开发规范主表
CREATE TABLE dev_spec (
    id         BIGINT       NOT NULL COMMENT '主键（雪花算法）',
    spec_id    VARCHAR(64)  NOT NULL COMMENT '业务主键（全局唯一）',
    name       VARCHAR(100) NOT NULL COMMENT '规范名称',
    spec_type  VARCHAR(20)  NOT NULL COMMENT '规范类型：UI/FRONTEND/BACKEND',
    content    MEDIUMTEXT   NULL     COMMENT '规范内容（Markdown 格式）',
    created_by VARCHAR(64)  NULL     COMMENT '创建人',
    created_at DATETIME     NOT NULL COMMENT '创建时间',
    updated_at DATETIME     NOT NULL COMMENT '更新时间',
    deleted    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_spec_id (spec_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '开发规范表';
