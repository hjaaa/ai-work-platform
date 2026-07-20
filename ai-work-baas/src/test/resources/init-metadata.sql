

CREATE TABLE `baas_project` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `project_ref` varchar(20) NOT NULL COMMENT '项目短标识,入 URL,非授权凭据',
  `name` varchar(64) NOT NULL COMMENT '项目名称',
  `db_name` varchar(64) NOT NULL COMMENT '项目 database 名',
  `status` varchar(16) NOT NULL COMMENT 'PROVISIONING/ACTIVE/MIGRATING/FAILED/DELETING/DELETED',
  `provision_step` varchar(32) DEFAULT NULL COMMENT '最近完成的开通步骤,失败重试用',
  `owner_user_id` bigint unsigned NOT NULL COMMENT '平台用户 ID,Studio 归属校验依据',
  `allowed_origins` json DEFAULT NULL COMMENT 'CORS 白名单,NULL 表示 *',
  `runtime_db_user` varchar(32) DEFAULT NULL COMMENT '项目 Runtime 账号名',
  `runtime_db_password_cipher` varchar(512) DEFAULT NULL COMMENT 'Runtime 账号密码密文(v1:{keyId}:...)',
  `ddl_fence_epoch` bigint unsigned NOT NULL DEFAULT 0 COMMENT '项目级单调 fencing 计数(spec §9.2)',
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
  `comment` varchar(2048) DEFAULT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'CREATING/ACTIVE/ALTERING/FAILED/CONFLICT/DELETED',
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
  `default_value` text DEFAULT NULL COMMENT '规范化默认值(true/false、数值、字符串原文或 CURRENT_TIMESTAMP)',
  `is_pk` tinyint unsigned NOT NULL DEFAULT 0,
  `is_auto_increment` tinyint unsigned NOT NULL DEFAULT 0,
  `is_unique` tinyint unsigned NOT NULL DEFAULT 0,
  `is_indexed` tinyint unsigned NOT NULL DEFAULT 0,
  `comment` varchar(1024) DEFAULT NULL,
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
  `operation_id` varchar(64) NOT NULL COMMENT '操作 ID,幂等键(HTTP 为客户端 UUID,内部操作为服务端 UUID)',
  `project_id` bigint unsigned NOT NULL,
  `operation_type` varchar(16) NOT NULL COMMENT 'create/alter/drop/acl-config/cleanup-drop/reconcile',
  `table_name` varchar(64) DEFAULT NULL COMMENT '目标表名(项目级操作为 NULL)',
  `table_id` bigint unsigned DEFAULT NULL COMMENT '不可变目标表 ID(baas_table.id)',
  `request_hash` char(64) NOT NULL COMMENT '操作指纹 SHA-256 hex(spec §9.2)',
  `result_snapshot` mediumtext DEFAULT NULL COMMENT '成功结果 JSON,幂等重放返回',
  `owner_token` varchar(64) DEFAULT NULL COMMENT '本次执行唯一标识;PENDING 为 NULL',
  `fence_epoch` bigint unsigned DEFAULT NULL COMMENT '取得所有权时的项目 epoch;历史/PENDING 为 NULL',
  `trigger_source` varchar(16) DEFAULT NULL COMMENT '仅 reconcile:MANUAL/SCHEDULED',
  `ddl_text` text NULL COMMENT '脱敏 DDL(默认值字面量以 ? 占位);纯元数据操作为 NULL',
  `step` varchar(32) NOT NULL COMMENT 'PREPARED/DDL_APPLIED/METADATA_APPLIED',
  `status` varchar(16) NOT NULL COMMENT 'PENDING/RUNNING/SUCCESS/FAILED',
  `error_msg` varchar(1024) DEFAULT NULL,
  `retry_count` int unsigned NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_operation` (`project_id`, `operation_id`),
  KEY `idx_status_update` (`status`, `update_time`)
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
