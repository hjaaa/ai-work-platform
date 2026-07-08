-- 飞书扫码登录：社交绑定关系表 + 存量钉钉数据迁移（2026-07-07）
-- 适用：已用旧版 db/ai_work.sql 初始化的存量环境；新环境直接使用最新 db/ai_work.sql，无需执行本脚本
-- 执行前请备份 ai_work 库

USE `ai_work`;

-- 1. 用户社交绑定关系表（物理删除，无 del_flag：唯一索引承载绑定唯一性）
CREATE TABLE IF NOT EXISTS `sys_user_social` (
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

-- 2. 存量钉钉绑定迁移
-- 若旧 sys_user.wx_ding_userid 存在重复，脚本会直接终止，避免把同一个钉钉标识错误绑定到多个平台用户。
-- 可先运行以下排查 SQL 清理脏数据，再重新执行本迁移：
-- SELECT wx_ding_userid, COUNT(*) AS duplicate_count, GROUP_CONCAT(user_id ORDER BY user_id) AS user_ids
-- FROM sys_user
-- WHERE wx_ding_userid IS NOT NULL AND wx_ding_userid != '' AND del_flag = '0'
-- GROUP BY wx_ding_userid
-- HAVING COUNT(*) > 1;
DROP PROCEDURE IF EXISTS `migrate_dingtalk_user_social`;
DELIMITER $$
CREATE PROCEDURE `migrate_dingtalk_user_social`()
BEGIN
  IF EXISTS (
    SELECT 1
    FROM `sys_user`
    WHERE `wx_ding_userid` IS NOT NULL
      AND `wx_ding_userid` != ''
      AND `del_flag` = '0'
    GROUP BY `wx_ding_userid`
    HAVING COUNT(*) > 1
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Duplicate active sys_user.wx_ding_userid found. Resolve duplicates before running 20260707_feishu_qr_login.sql.';
  END IF;

  INSERT INTO `sys_user_social` (`id`, `user_id`, `type`, `identify`, `create_by`, `create_time`)
  SELECT `u`.`user_id`, `u`.`user_id`, 'DINGTALK', `u`.`wx_ding_userid`, ' ', NOW()
  FROM `sys_user` `u`
  WHERE `u`.`wx_ding_userid` IS NOT NULL
    AND `u`.`wx_ding_userid` != ''
    AND `u`.`del_flag` = '0'
    AND NOT EXISTS (
      SELECT 1
      FROM `sys_user_social` `sus`
      WHERE `sus`.`type` = 'DINGTALK'
        AND `sus`.`identify` = `u`.`wx_ding_userid`
    )
    AND NOT EXISTS (
      SELECT 1
      FROM `sys_user_social` `sus`
      WHERE `sus`.`user_id` = `u`.`user_id`
        AND `sus`.`type` = 'DINGTALK'
    );
END$$
DELIMITER ;

CALL `migrate_dingtalk_user_social`();
DROP PROCEDURE IF EXISTS `migrate_dingtalk_user_social`;

-- 3. 飞书应用凭证模板：替换尖括号占位后手工执行；切勿将真实凭证提交到 git
-- INSERT INTO `sys_social_details` (`id`, `type`, `remark`, `app_id`, `app_secret`, `redirect_url`)
-- VALUES (1930000000000000001, 'FEISHU', '飞书扫码登录', '<FEISHU_APP_ID>', '<FEISHU_APP_SECRET>', 'http://localhost:5173/social-callback.html');
-- 注意：redirect_url 必须与前端授权时的 redirect_uri 逐字符一致（含协议/端口/路径），生产环境替换为正式域名
