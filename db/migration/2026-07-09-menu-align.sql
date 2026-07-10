-- 2026-07-09 侧边栏菜单对齐设计稿
-- 目标：新增 AI 平台业务菜单(AI 能力/任务与数据/协作)，把三层旧系统菜单压平为两层归入"系统"组。
-- 幂等：可重复执行。适用于已初始化的开发库。

START TRANSACTION;

-- 1) 新增业务分组与叶子（id 段 3000-3299）
DELETE FROM `sys_menu` WHERE `menu_id` IN
  (3000,3001,3002,3003,3004,3100,3101,3102,3103,3200,3201,3202);
INSERT INTO `sys_menu`
  (`menu_id`,`name`,`permission`,`path`,`component`,`parent_id`,`icon`,`visible`,`sort_order`,`keep_alive`,`embedded`,`menu_type`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`)
VALUES
  (3000,'AI 能力',NULL,'/ai',NULL,-1,'agents','1',10,'0','0','0','admin','2026-07-09 00:00:00','admin','2026-07-09 00:00:00','0'),
  (3001,'模型管理',NULL,'/models',NULL,3000,'models','1',10,'0','0','0','admin','2026-07-09 00:00:00','admin','2026-07-09 00:00:00','0'),
  (3002,'提示词库',NULL,'/prompts',NULL,3000,'prompts','1',20,'0','0','0','admin','2026-07-09 00:00:00','admin','2026-07-09 00:00:00','0'),
  (3003,'智能体',NULL,'/agents',NULL,3000,'agents','1',30,'0','0','0','admin','2026-07-09 00:00:00','admin','2026-07-09 00:00:00','0'),
  (3004,'知识库',NULL,'/kb',NULL,3000,'kb','1',40,'0','0','0','admin','2026-07-09 00:00:00','admin','2026-07-09 00:00:00','0'),
  (3100,'任务与数据',NULL,'/data-tasks',NULL,-1,'tasks','1',20,'0','0','0','admin','2026-07-09 00:00:00','admin','2026-07-09 00:00:00','0'),
  (3101,'任务中心',NULL,'/tasks',NULL,3100,'tasks','1',10,'0','0','0','admin','2026-07-09 00:00:00','admin','2026-07-09 00:00:00','0'),
  (3102,'数据集',NULL,'/datasets',NULL,3100,'datasets','1',20,'0','0','0','admin','2026-07-09 00:00:00','admin','2026-07-09 00:00:00','0'),
  (3103,'标注管理',NULL,'/label',NULL,3100,'label','1',30,'0','0','0','admin','2026-07-09 00:00:00','admin','2026-07-09 00:00:00','0'),
  (3200,'协作',NULL,'/collab',NULL,-1,'members','1',30,'0','0','0','admin','2026-07-09 00:00:00','admin','2026-07-09 00:00:00','0'),
  (3201,'成员管理',NULL,'/members',NULL,3200,'members','1',10,'0','0','0','admin','2026-07-09 00:00:00','admin','2026-07-09 00:00:00','0'),
  (3202,'团队空间',NULL,'/spaces',NULL,3200,'spaces','1',20,'0','0','0','admin','2026-07-09 00:00:00','admin','2026-07-09 00:00:00','0');

-- 2) 系统组：复用 2000，改名"系统"并排到业务组之后
UPDATE `sys_menu` SET `name`='系统', `icon`='settings', `sort_order`=40 WHERE `menu_id`=2000;

-- 3) 压平：三级功能页重定父到 2000（其余系统项本就是 2000 直属）
UPDATE `sys_menu` SET `parent_id`=2000 WHERE `menu_id` IN (1100,1200,1400,1600,2100);
UPDATE `sys_menu` SET `icon`='members', `sort_order`=10 WHERE `menu_id`=1100;
UPDATE `sys_menu` SET `icon`='apps', `sort_order`=20 WHERE `menu_id`=1200;
UPDATE `sys_menu` SET `icon`='members', `sort_order`=30 WHERE `menu_id`=1400;
UPDATE `sys_menu` SET `icon`='roles', `sort_order`=40 WHERE `menu_id`=1600;
UPDATE `sys_menu` SET `icon`='settings', `sort_order`=50 WHERE `menu_id`=2200;
UPDATE `sys_menu` SET `icon`='settings', `sort_order`=60 WHERE `menu_id`=2210;
UPDATE `sys_menu` SET `icon`='logs', `sort_order`=70 WHERE `menu_id`=2100;
UPDATE `sys_menu` SET `icon`='apps', `sort_order`=80 WHERE `menu_id`=2400;
UPDATE `sys_menu` SET `icon`='roles', `sort_order`=90 WHERE `menu_id`=2600;
UPDATE `sys_menu` SET `icon`='kb', `sort_order`=100 WHERE `menu_id`=2906;
UPDATE `sys_menu` SET `icon`='label', `sort_order`=110 WHERE `menu_id`=2900;
UPDATE `sys_menu` SET `icon`='label', `sort_order`=120 WHERE `menu_id`=2920;

-- 4) 角色权限：复用旧"角色管理"1300，移入"协作"组
UPDATE `sys_menu` SET `name`='角色权限', `icon`='roles', `parent_id`=3200, `sort_order`=30 WHERE `menu_id`=1300;

-- 5) 开发平台/基础工具：仅调排序，排在系统之后
UPDATE `sys_menu` SET `icon`='apps', `sort_order`=50 WHERE `menu_id`=9000;
UPDATE `sys_menu` SET `icon`='settings', `sort_order`=60 WHERE `menu_id`=9910;

-- 6) 软删中间目录节点与杂项运营菜单
UPDATE `sys_menu` SET `del_flag`='1' WHERE `menu_id` IN (1000,2001,1700,2500,2910);

-- 7) 授权：admin(role 1) 关联新业务菜单
DELETE FROM `sys_role_menu` WHERE `role_id`=1 AND `menu_id` IN
  (3000,3001,3002,3003,3004,3100,3101,3102,3103,3200,3201,3202);
INSERT INTO `sys_role_menu` (`role_id`,`menu_id`) VALUES
  (1,3000),(1,3001),(1,3002),(1,3003),(1,3004),
  (1,3100),(1,3101),(1,3102),(1,3103),
  (1,3200),(1,3201),(1,3202);

-- 8) 清理软删菜单的所有角色关联
DELETE FROM `sys_role_menu` WHERE `menu_id` IN (1000,2001,1700,2500,2910);

-- 9) 授权：普通用户(role 2) 只给业务组
DELETE FROM `sys_role_menu` WHERE `role_id`=2 AND `menu_id` IN
  (3000,3001,3002,3003,3004,3100,3101,3102,3103,3200,3201,3202);
INSERT INTO `sys_role_menu` (`role_id`,`menu_id`) VALUES
  (2,3000),(2,3001),(2,3002),(2,3003),(2,3004),
  (2,3100),(2,3101),(2,3102),(2,3103),
  (2,3200),(2,3201),(2,3202);

COMMIT;
