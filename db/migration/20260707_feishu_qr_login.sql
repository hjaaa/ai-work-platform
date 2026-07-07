-- 飞书扫码登录：社交绑定关系表 + 存量钉钉数据迁移（2026-07-07）
-- 适用：已用旧版 db/ai_work.sql 初始化的存量环境；新环境直接使用最新 db/ai_work.sql，无需执行本脚本
-- 执行前请备份 ai_work 库

USE `ai_work`;

-- 1. 用户社交绑定关系表（物理删除，无 del_flag：唯一索引承载绑定唯一性）
DROP TABLE IF EXISTS `sys_user_social`;
CREATE TABLE `sys_user_social` (
  `id` bigint(20) NOT NULL COMMENT '主键（应用层雪花ID；迁移行复用 user_id）',
  `user_id` bigint(20) NOT NULL COMMENT '平台用户ID',
  `type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '社交类型（DINGTALK/FEISHU）',
  `identify` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '第三方用户标识（openId）',
  `create_by` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT ' ' COMMENT '创建人',
  `update_by` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT ' ' COMMENT '修改人',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_type_identify` (`type`,`identify`) USING BTREE,
  UNIQUE KEY `uk_user_type` (`user_id`,`type`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户社交绑定关系表（物理删除，无 del_flag，唯一索引承载绑定唯一性）';

-- 2. 存量钉钉绑定迁移（迁移行 id 直接复用 user_id：每用户每类型仅一行，业务侧雪花 ID 远大于用户 ID，无冲突）
INSERT INTO `sys_user_social` (`id`, `user_id`, `type`, `identify`, `create_by`, `create_time`)
SELECT `user_id`, `user_id`, 'DINGTALK', `wx_ding_userid`, ' ', NOW()
FROM `sys_user`
WHERE `wx_ding_userid` IS NOT NULL AND `wx_ding_userid` != '' AND `del_flag` = '0';

-- 3. 飞书应用凭证模板：替换尖括号占位后手工执行；切勿将真实凭证提交到 git
-- INSERT INTO `sys_social_details` (`id`, `type`, `remark`, `app_id`, `app_secret`, `redirect_url`)
-- VALUES (1930000000000000001, 'FEISHU', '飞书扫码登录', '<FEISHU_APP_ID>', '<FEISHU_APP_SECRET>', 'http://localhost:5173/social-callback.html');
-- 注意：redirect_url 必须与前端授权时的 redirect_uri 逐字符一致（含协议/端口/路径），生产环境替换为正式域名
