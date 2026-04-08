-- 项目表新增代码目录和 clone 错误信息字段
ALTER TABLE project
    ADD COLUMN code_path VARCHAR(512) COMMENT 'Git clone 的代码存储目录' AFTER workspace_path,
    ADD COLUMN clone_error_message TEXT COMMENT 'Git clone 失败时的错误信息' AFTER code_path;
