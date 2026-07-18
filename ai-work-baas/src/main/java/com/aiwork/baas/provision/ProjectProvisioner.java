/*
 *
 *      Copyright (c) 2018-2025, lengleng All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 * Neither the name of the pig4cloud.com developer nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 * Author: lengleng (wangiegie@gmail.com)
 *
 */

package com.aiwork.baas.provision;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.regex.Pattern;

/**
 * 项目库开通器：仅由管理面生命周期流程调用，使用 Provisioner 高权限账号(spec §10.1)。
 * 所有方法均支持失败重试；创建运行时账号时以 ALTER USER 收敛密码。
 *
 * @author ai-work
 * @date 2026/07/17
 */
public class ProjectProvisioner {

    private static final Pattern RUNTIME_PASSWORD_PATTERN = Pattern.compile("[A-Za-z0-9]{16,64}");

    private static final String CREATE_DATABASE_SQL = "CREATE DATABASE IF NOT EXISTS `%s` "
            + "DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci";

    private static final String CREATE_USER_SQL = "CREATE USER IF NOT EXISTS '%s'@'%%' IDENTIFIED BY '%s'";

    private static final String ALTER_USER_SQL = "ALTER USER '%s'@'%%' IDENTIFIED BY '%s'";

    private static final String GRANT_RUNTIME_PRIVILEGES_SQL = "GRANT SELECT, INSERT, UPDATE, DELETE ON `%s`.* "
            + "TO '%s'@'%%'";

    private static final String CREATE_USERS_TABLE_SQL = "CREATE TABLE IF NOT EXISTS `%s`._users ("
            + "id bigint unsigned NOT NULL AUTO_INCREMENT, email varchar(255) NOT NULL, "
            + "password_hash varchar(100) NOT NULL, "
            + "raw_meta json DEFAULT NULL, create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP, "
            + "update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
            + "PRIMARY KEY (id), UNIQUE KEY uk_email (email)) ENGINE=InnoDB";

    private static final String CREATE_SESSIONS_TABLE_SQL = "CREATE TABLE IF NOT EXISTS `%s`._sessions ("
            + "id bigint unsigned NOT NULL AUTO_INCREMENT, user_id bigint unsigned NOT NULL, "
            + "status varchar(16) NOT NULL DEFAULT 'ACTIVE', "
            + "create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP, "
            + "update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
            + "last_active_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (id), "
            + "KEY idx_user (user_id)) ENGINE=InnoDB";

    private static final String CREATE_REFRESH_TOKENS_TABLE_SQL = "CREATE TABLE IF NOT EXISTS `%s`._refresh_tokens ("
            + "id bigint unsigned NOT NULL AUTO_INCREMENT, token_hash char(64) NOT NULL, "
            + "session_id bigint unsigned NOT NULL, "
            + "expire_time datetime NOT NULL, consumed_at datetime DEFAULT NULL, "
            + "replacement_token_id bigint unsigned DEFAULT NULL, "
            + "reuse_grace_until datetime DEFAULT NULL, replay_payload_ciphertext text DEFAULT NULL, "
            + "create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP, "
            + "update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, PRIMARY KEY (id), "
            + "UNIQUE KEY uk_token_hash (token_hash), KEY idx_session (session_id)) ENGINE=InnoDB";

    private static final String DROP_DATABASE_SQL = "DROP DATABASE IF EXISTS `%s`";

    private static final String DROP_USER_SQL = "DROP USER IF EXISTS '%s'@'%%'";

    private final JdbcTemplate jdbcTemplate;

    public ProjectProvisioner(DataSource provisionerDataSource) {
        this.jdbcTemplate = new JdbcTemplate(provisionerDataSource);
    }

    public void createDatabase(String databaseName) {
        IdentifierValidator.validate(databaseName);
        jdbcTemplate.execute(CREATE_DATABASE_SQL.formatted(databaseName));
    }

    public void createRuntimeUser(String username, String password, String databaseName) {
        IdentifierValidator.validate(username);
        IdentifierValidator.validate(databaseName);
        validateRuntimePassword(password);

        jdbcTemplate.execute(CREATE_USER_SQL.formatted(username, password));
        jdbcTemplate.execute(ALTER_USER_SQL.formatted(username, password));
        jdbcTemplate.execute(GRANT_RUNTIME_PRIVILEGES_SQL.formatted(databaseName, username));
    }

    public void initSystemTables(String databaseName) {
        IdentifierValidator.validate(databaseName);
        jdbcTemplate.execute(CREATE_USERS_TABLE_SQL.formatted(databaseName));
        jdbcTemplate.execute(CREATE_SESSIONS_TABLE_SQL.formatted(databaseName));
        jdbcTemplate.execute(CREATE_REFRESH_TOKENS_TABLE_SQL.formatted(databaseName));
    }

    public void dropDatabaseAndUser(String databaseName, String username) {
        IdentifierValidator.validate(databaseName);
        IdentifierValidator.validate(username);
        jdbcTemplate.execute(DROP_DATABASE_SQL.formatted(databaseName));
        jdbcTemplate.execute(DROP_USER_SQL.formatted(username));
    }

    private void validateRuntimePassword(String password) {
        if (password == null || !RUNTIME_PASSWORD_PATTERN.matcher(password).matches()) {
            throw new IllegalArgumentException("runtime password violates policy");
        }
    }

}
