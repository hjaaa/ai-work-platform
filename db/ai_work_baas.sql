# Nacos 配置 ai-work-baas-dev.yml 模板(部署时创建,不入 Git):
# spring:
#   datasource:
#     type: com.alibaba.druid.pool.DruidDataSource
#     driver-class-name: com.mysql.cj.jdbc.Driver
#     username: root
#     password: <元数据库密码>
#     url: jdbc:mysql://ai-work-mysql:3306/ai_work_baas?characterEncoding=utf8&useSSL=false&serverTimezone=GMT%2B8
# baas:
#   provisioner:
#     url: jdbc:mysql://ai-work-mysql:3306/mysql?characterEncoding=utf8&useSSL=false&serverTimezone=GMT%2B8
#     username: <高权限账号>
#     password: <密码>
# 环境变量(容器注入,不入任何配置文件):BAAS_MASTER_KEYS=k1=<base64 32字节>  BAAS_ACTIVE_KEY_ID=k1

CREATE DATABASE IF NOT EXISTS `ai_work_baas` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `ai_work_baas`;

CREATE TABLE `baas_project` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `project_ref` varchar(20) NOT NULL COMMENT '项目短标识,入 URL,非授权凭据',
  `name` varchar(64) NOT NULL COMMENT '项目名称',
  `db_name` varchar(64) NOT NULL COMMENT '项目 database 名',
  `status` varchar(16) NOT NULL COMMENT 'PROVISIONING/ACTIVE/FAILED/DELETING/DELETED',
  `provision_step` varchar(32) DEFAULT NULL COMMENT '最近完成的开通步骤,失败重试用',
  `owner_user_id` bigint unsigned NOT NULL COMMENT '平台用户 ID,Studio 归属校验依据',
  `allowed_origins` json DEFAULT NULL COMMENT 'CORS 白名单,NULL 表示 *',
  `runtime_db_user` varchar(32) DEFAULT NULL COMMENT '项目 Runtime 账号名',
  `runtime_db_password_cipher` varchar(512) DEFAULT NULL COMMENT 'Runtime 账号密码密文(v1:{keyId}:...)',
  `delete_after` datetime DEFAULT NULL COMMENT '延迟物理清理时间点',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_ref` (`project_ref`),
  KEY `idx_owner` (`owner_user_id`)
) ENGINE=InnoDB COMMENT='BaaS 项目';

CREATE TABLE `baas_jwt_key` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `project_id` bigint unsigned NOT NULL,
  `kid` varchar(36) NOT NULL,
  `secret_cipher` varchar(512) NOT NULL COMMENT 'HS256 secret 密文',
  `status` varchar(16) NOT NULL COMMENT 'CURRENT/PREVIOUS/REVOKED',
  `valid_until` datetime DEFAULT NULL COMMENT 'PREVIOUS 状态的失效时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_kid` (`project_id`, `kid`)
) ENGINE=InnoDB COMMENT='项目 JWT 签名密钥';

CREATE TABLE `baas_api_key` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `project_id` bigint unsigned NOT NULL,
  `key_type` varchar(16) NOT NULL COMMENT 'PUBLISHABLE/SECRET',
  `key_hash` char(64) NOT NULL COMMENT 'SHA-256 hex',
  `key_prefix` varchar(16) NOT NULL COMMENT '明文前缀,列表展示用',
  `status` varchar(16) NOT NULL COMMENT 'ACTIVE/REVOKED',
  `last_used_time` datetime DEFAULT NULL,
  `revoke_time` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_key_hash` (`key_hash`),
  KEY `idx_project` (`project_id`)
) ENGINE=InnoDB COMMENT='项目 API Key(opaque,哈希存储)';

CREATE TABLE `baas_table` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `project_id` bigint unsigned NOT NULL,
  `table_name` varchar(64) NOT NULL,
  `comment` varchar(255) DEFAULT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DELETED/CONFLICT',
  `owner_column` varchar(64) DEFAULT NULL COMMENT '行归属列,NULL 未启用',
  `delete_after` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_table` (`project_id`, `table_name`)
) ENGINE=InnoDB COMMENT='项目表元数据';

CREATE TABLE `baas_column` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `table_id` bigint unsigned NOT NULL,
  `column_name` varchar(64) NOT NULL,
  `data_type` varchar(32) NOT NULL COMMENT 'spec §13 类型白名单',
  `length` int unsigned DEFAULT NULL,
  `scale` int unsigned DEFAULT NULL,
  `is_nullable` tinyint unsigned NOT NULL DEFAULT 1,
  `default_value` varchar(255) DEFAULT NULL,
  `is_pk` tinyint unsigned NOT NULL DEFAULT 0,
  `is_auto_increment` tinyint unsigned NOT NULL DEFAULT 0,
  `is_unique` tinyint unsigned NOT NULL DEFAULT 0,
  `is_indexed` tinyint unsigned NOT NULL DEFAULT 0,
  `comment` varchar(255) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_table_column` (`table_id`, `column_name`)
) ENGINE=InnoDB COMMENT='项目列元数据';

CREATE TABLE `baas_table_acl` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `table_id` bigint unsigned NOT NULL,
  `role` varchar(16) NOT NULL COMMENT 'anon/authenticated',
  `is_can_select` tinyint unsigned NOT NULL DEFAULT 0,
  `is_can_insert` tinyint unsigned NOT NULL DEFAULT 0,
  `is_can_update` tinyint unsigned NOT NULL DEFAULT 0,
  `is_can_delete` tinyint unsigned NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_table_role` (`table_id`, `role`)
) ENGINE=InnoDB COMMENT='表级 ACL,新表默认全关';

CREATE TABLE `baas_ddl_log` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `operation_id` varchar(64) NOT NULL COMMENT '客户端操作 ID,幂等键',
  `project_id` bigint unsigned NOT NULL,
  `ddl_text` text NOT NULL,
  `step` varchar(32) NOT NULL,
  `status` varchar(16) NOT NULL COMMENT 'RUNNING/SUCCESS/FAILED',
  `error_msg` varchar(1024) DEFAULT NULL,
  `retry_count` int unsigned NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_operation` (`operation_id`)
) ENGINE=InnoDB COMMENT='Schema 操作日志';

CREATE TABLE `baas_audit_log` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `project_id` bigint unsigned DEFAULT NULL,
  `operator_user_id` bigint unsigned NOT NULL,
  `action` varchar(64) NOT NULL COMMENT '如 PROJECT_CREATE/KEY_REVOKE/JWT_EMERGENCY_ROTATE',
  `detail` varchar(1024) DEFAULT NULL COMMENT '脱敏后的操作详情',
  `level` varchar(8) NOT NULL DEFAULT 'INFO' COMMENT 'INFO/HIGH',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_project_time` (`project_id`, `create_time`)
) ENGINE=InnoDB COMMENT='敏感操作审计';
