/*
 *
 *      Copyright (c) 2018-2026, lengleng All rights reserved.
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

package com.aiwork.baas.mapper;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.nio.file.Path;
import java.nio.file.Files;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class PlanBMigrationSqlTest {

    @Container
    static MySQLContainer mysql = new MySQLContainer("mysql:8.4").withUsername("root")
        .withPassword("root")
        .withDatabaseName("ai_work_baas");

    static JdbcTemplate jdbc;

    @BeforeAll
    static void migratePlanABaseline() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(), "root", "root");
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE baas_project (id bigint unsigned NOT NULL AUTO_INCREMENT, "
                + "project_ref varchar(20) NOT NULL, name varchar(64) NOT NULL, db_name varchar(64) NOT NULL, "
                + "status varchar(16) NOT NULL, provision_step varchar(32), owner_user_id bigint unsigned NOT NULL, "
                + "allowed_origins json, runtime_db_user varchar(32), runtime_db_password_cipher varchar(512), "
                + "delete_after datetime, create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                + "PRIMARY KEY (id), UNIQUE KEY uk_project_ref(project_ref), KEY idx_owner(owner_user_id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE baas_table (id bigint unsigned NOT NULL AUTO_INCREMENT, "
                + "project_id bigint unsigned NOT NULL, table_name varchar(64) NOT NULL, comment varchar(255), "
                + "status varchar(16) NOT NULL DEFAULT 'ACTIVE', owner_column varchar(64), delete_after datetime, "
                + "create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP, update_time datetime NOT NULL "
                + "DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, PRIMARY KEY (id), "
                + "UNIQUE KEY uk_project_table(project_id,table_name)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE baas_column (id bigint unsigned NOT NULL AUTO_INCREMENT, "
                + "table_id bigint unsigned NOT NULL, column_name varchar(64) NOT NULL, data_type varchar(32) NOT NULL, "
                + "length int unsigned, scale int unsigned, is_nullable tinyint unsigned NOT NULL DEFAULT 1, "
                + "default_value varchar(255), is_pk tinyint unsigned NOT NULL DEFAULT 0, "
                + "is_auto_increment tinyint unsigned NOT NULL DEFAULT 0, is_unique tinyint unsigned NOT NULL DEFAULT 0, "
                + "is_indexed tinyint unsigned NOT NULL DEFAULT 0, comment varchar(255), "
                + "create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP, update_time datetime NOT NULL "
                + "DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, PRIMARY KEY (id), "
                + "UNIQUE KEY uk_table_column(table_id,column_name)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE baas_ddl_log (id bigint unsigned NOT NULL AUTO_INCREMENT, operation_id varchar(64) "
                + "NOT NULL, project_id bigint unsigned NOT NULL, ddl_text text NOT NULL, step varchar(32) NOT NULL, "
                + "status varchar(16) NOT NULL, error_msg varchar(1024), retry_count int unsigned NOT NULL DEFAULT 0, "
                + "create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP, update_time datetime NOT NULL "
                + "DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, PRIMARY KEY (id), "
                + "UNIQUE KEY uk_operation (operation_id)) ENGINE=InnoDB");
        jdbc.update("INSERT INTO baas_ddl_log(operation_id,project_id,ddl_text,step,status) "
                + "VALUES ('legacy-op',1,'ALTER secret','PREPARED','RUNNING')");

        String repositoryRoot = System.getProperty("maven.multiModuleProjectDirectory",
                Path.of("..").toAbsolutePath().normalize().toString());
        Path script = Path.of(repositoryRoot, "db", "ai_work_baas_plan_b_migration.sql");
        executeMysqlScript(dataSource, script);
        executeMysqlScript(dataSource, script);
    }

    @Test
    void migrationRunsAndMatchesTerminalColumnContract() {
        assertThat(jdbc.queryForObject("SELECT COLUMN_TYPE FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA='ai_work_baas' AND TABLE_NAME='baas_ddl_log' AND COLUMN_NAME='id'",
                String.class)).isEqualTo("bigint unsigned");
        assertThat(jdbc.queryForObject("SELECT COLUMN_TYPE FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA='ai_work_baas' AND TABLE_NAME='baas_ddl_log' AND COLUMN_NAME='project_id'",
                String.class)).isEqualTo("bigint unsigned");
        assertThat(jdbc.queryForObject("SELECT COLUMN_TYPE FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA='ai_work_baas' AND TABLE_NAME='baas_ddl_log' AND COLUMN_NAME='retry_count'",
                String.class)).isEqualTo("int unsigned");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA='ai_work_baas' AND TABLE_NAME='baas_ddl_log' "
                + "AND COLUMN_NAME='update_time'", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COLUMN_TYPE FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA='ai_work_baas' AND TABLE_NAME='baas_ddl_log' AND COLUMN_NAME='table_id'",
                String.class)).isEqualTo("bigint unsigned");
        assertThat(jdbc.queryForObject("SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA='ai_work_baas' AND TABLE_NAME='baas_ddl_log' "
                + "AND COLUMN_NAME='operation_type'", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA='ai_work_baas' AND TABLE_NAME='baas_ddl_log' "
                + "AND COLUMN_NAME='request_hash'", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM baas_ddl_log WHERE operation_id='legacy-op'",
                String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_msg FROM baas_ddl_log WHERE operation_id='legacy-op'",
                String.class)).isEqualTo("LEGACY_FAILURE_REDACTED");
        assertThat(jdbc.queryForObject("SELECT ddl_text FROM baas_ddl_log WHERE operation_id='legacy-op'",
                String.class)).isEqualTo("LEGACY_DDL_REDACTED");
    }

    private static void executeMysqlScript(DataSource dataSource, Path script) throws Exception {
        String delimiter = ";";
        StringBuilder statement = new StringBuilder();
        try (var connection = dataSource.getConnection()) {
            for (String line : Files.readAllLines(script)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("DELIMITER ")) {
                    delimiter = trimmed.substring("DELIMITER ".length());
                    continue;
                }
                statement.append(line).append('\n');
                if (trimmed.endsWith(delimiter)) {
                    int end = statement.lastIndexOf(delimiter);
                    String sql = statement.substring(0, end).trim();
                    statement.setLength(0);
                    if (!sql.isEmpty()) {
                        try (var jdbcStatement = connection.createStatement()) {
                            jdbcStatement.execute(sql);
                        }
                    }
                }
            }
        }
    }

}
