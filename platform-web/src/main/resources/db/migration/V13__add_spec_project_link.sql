-- 规范与项目关联表（物理删除，通过唯一键保证幂等）
CREATE TABLE spec_project_link (
    id         BIGINT      NOT NULL COMMENT '主键（雪花算法）',
    spec_id    VARCHAR(64) NOT NULL COMMENT '规范业务主键',
    project_id VARCHAR(64) NOT NULL COMMENT '项目业务主键',
    created_at DATETIME    NOT NULL COMMENT '关联时间',
    updated_at DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_spec_project (spec_id, project_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '规范-项目关联表';
