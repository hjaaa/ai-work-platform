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

import com.mysql.cj.jdbc.MysqlDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 项目库开通器集成测试。
 *
 * @author ai-work
 * @date 2026/07/17
 */
@Testcontainers
class ProjectProvisionerTest {

    @Container
    static MySQLContainer mysql = new MySQLContainer("mysql:8.4").withUsername("root").withPassword("root");

    private ProjectProvisioner provisioner;

    private static MysqlDataSource dataSource(String database, String username, String password) {
        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setUrl(mysql.getJdbcUrl().replaceAll("/[^/?]+(\\?|$)", "/" + database + "$1"));
        dataSource.setUser(username);
        dataSource.setPassword(password);
        return dataSource;
    }

    @BeforeEach
    void setUp() {
        provisioner = new ProjectProvisioner(dataSource("mysql", "root", "root"));
    }

    @Test
    void provisionCreatesDatabaseUserAndSystemTables() {
        provisioner.createDatabase("baas_testref");
        provisioner.createRuntimeUser("rt_testref", "Aa12345678901234", "baas_testref");
        provisioner.initSystemTables("baas_testref");

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource("baas_testref", "rt_testref", "Aa12345678901234"));
        jdbcTemplate.update("INSERT INTO _users(email, password_hash) VALUES ('a@b.c', 'h')");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM _users", Long.class)).isEqualTo(1L);
        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> jdbcTemplate.execute("CREATE TABLE t1(id int)"));
        assertThat(exception.getCause()).hasMessageContaining("CREATE command denied");
    }

    @Test
    void systemTablesUseSignedBigintIdsAndContainAuditTimestamps() {
        String databaseName = "baas_schema_ref";
        provisioner.createDatabase(databaseName);
        provisioner.initSystemTables(databaseName);

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource("mysql", "root", "root"));
        Map<String, String> idColumnTypes = jdbcTemplate.query(
                "SELECT table_name, column_name, column_type FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name IN ('_users', '_sessions', '_refresh_tokens') "
                        + "AND column_name IN ('id', 'user_id', 'session_id', 'replacement_token_id')",
                resultSet -> {
                    Map<String, String> columnTypes = new java.util.HashMap<>(8);
                    while (resultSet.next()) {
                        columnTypes.put(resultSet.getString("table_name") + "." + resultSet.getString("column_name"),
                                resultSet.getString("column_type"));
                    }
                    return columnTypes;
                }, databaseName);
        assertThat(idColumnTypes).hasSize(6).containsAllEntriesOf(Map.of(
                "_users.id", "bigint",
                "_sessions.id", "bigint",
                "_sessions.user_id", "bigint",
                "_refresh_tokens.id", "bigint",
                "_refresh_tokens.session_id", "bigint",
                "_refresh_tokens.replacement_token_id", "bigint"));

        Long commonFieldCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name IN ('_users', '_sessions', '_refresh_tokens') "
                        + "AND column_name IN ('id', 'create_time', 'update_time')",
                Long.class, databaseName);
        assertThat(commonFieldCount).isEqualTo(9L);
    }

    @Test
    void createRuntimeUserConvergesPassword() {
        provisioner.createDatabase("baas_pwref");
        provisioner.createRuntimeUser("rt_pwref", "Aa12345678901234", "baas_pwref");
        provisioner.createRuntimeUser("rt_pwref", "Bb98765432109876", "baas_pwref");

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource("baas_pwref", "rt_pwref", "Bb98765432109876"));
        assertThat(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
    }

    @Test
    void invalidIdentifierRejected() {
        assertThatThrownBy(() -> provisioner.createDatabase("Bad-Name;drop"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdentifierValidator.validate("select")).isInstanceOf(IllegalArgumentException.class);
    }

}
