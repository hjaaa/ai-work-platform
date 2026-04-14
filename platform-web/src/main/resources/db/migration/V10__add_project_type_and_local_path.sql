-- 新增项目类型字段：区分 git 远程项目和 local 本地项目
ALTER TABLE project
    ADD COLUMN project_type VARCHAR(32) DEFAULT 'git' COMMENT '项目类型: git-远程仓库, local-本地项目' AFTER project_id,
    ADD COLUMN local_path VARCHAR(512) COMMENT '本地项目路径（仅 local 类型使用）' AFTER workspace_path;
