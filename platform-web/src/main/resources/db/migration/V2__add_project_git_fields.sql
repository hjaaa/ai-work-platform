-- 项目表新增 Git 相关字段
ALTER TABLE project
    ADD COLUMN git_url VARCHAR(512) COMMENT 'Git 仓库地址' AFTER description,
    ADD COLUMN default_branch VARCHAR(128) DEFAULT 'main' COMMENT '默认分支' AFTER git_url;
