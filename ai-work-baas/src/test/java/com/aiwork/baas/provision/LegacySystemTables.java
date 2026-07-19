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

public final class LegacySystemTables {

    private LegacySystemTables() {
    }

    public static void create(JdbcTemplate jdbc, String dbName) {
        jdbc.execute(("CREATE TABLE `%s`._users (id bigint unsigned NOT NULL AUTO_INCREMENT, "
                + "email varchar(255) NOT NULL, password_hash varchar(100) NOT NULL, raw_meta json DEFAULT NULL, "
                + "create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                + "PRIMARY KEY (id), UNIQUE KEY uk_email (email)) ENGINE=InnoDB").formatted(dbName));
        jdbc.execute(("CREATE TABLE `%s`._sessions (id bigint unsigned NOT NULL AUTO_INCREMENT, "
                + "user_id bigint unsigned NOT NULL, status varchar(16) NOT NULL DEFAULT 'ACTIVE', "
                + "create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                + "last_active_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (id), "
                + "KEY idx_user (user_id)) ENGINE=InnoDB").formatted(dbName));
        jdbc.execute(("CREATE TABLE `%s`._refresh_tokens (id bigint unsigned NOT NULL AUTO_INCREMENT, "
                + "token_hash char(64) NOT NULL, session_id bigint unsigned NOT NULL, expire_time datetime NOT NULL, "
                + "consumed_at datetime DEFAULT NULL, replacement_token_id bigint unsigned DEFAULT NULL, "
                + "reuse_grace_until datetime DEFAULT NULL, replay_payload_ciphertext text DEFAULT NULL, "
                + "create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                + "PRIMARY KEY (id), UNIQUE KEY uk_token_hash (token_hash), KEY idx_session (session_id)) "
                + "ENGINE=InnoDB").formatted(dbName));
    }

}
