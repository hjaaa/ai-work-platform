-- 飞书扫码登录 JIT 自动建号:部门映射列、平台企业内用户 ID 列、功能开关参数
ALTER TABLE `sys_dept`
    ADD COLUMN `feishu_dept_id` varchar(64) DEFAULT NULL COMMENT '飞书 open_department_id 映射',
    ADD UNIQUE INDEX `uk_feishu_dept_id` (`feishu_dept_id`);

ALTER TABLE `sys_user_social`
    ADD COLUMN `tenant_user_id` varchar(64) DEFAULT NULL COMMENT '用户在该社交平台企业内的ID(如飞书user_id)';

INSERT INTO `sys_public_param` (`public_id`, `public_name`, `public_key`, `public_value`, `status`, `validate_code`, `create_by`, `update_by`, `create_time`, `public_type`, `system_flag`, `del_flag`)
VALUES (31, '飞书扫码自动建号开关', 'FEISHU_JIT_ENABLE', '1', '0', NULL, 'admin', 'admin', '2026-07-10 00:00:00', '2', '1', '0');
