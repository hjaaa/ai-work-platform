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

package com.aiwork.baas.provision;

import com.aiwork.baas.ddl.inspect.PhysicalTable;
import com.aiwork.baas.ddl.inspect.SchemaInspector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class SystemTableBaselineTest {

    @Container
    static MySQLContainer mysql = new MySQLContainer("mysql:8.4").withUsername("root").withPassword("root");

    static JdbcTemplate rootJdbc;

    static ProjectProvisioner provisioner;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                mysql.getJdbcUrl().replace("/" + mysql.getDatabaseName(), "/mysql"), "root", "root");
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        rootJdbc = new JdbcTemplate(dataSource);
        provisioner = new ProjectProvisioner(dataSource);
    }

    private static Map<String, PhysicalTable> readSystemTables(String dbName) {
        Map<String, PhysicalTable> tables = new HashMap<>();
        for (String tableName : SystemTableManifest.SYSTEM_TABLE_NAMES) {
            tables.put(tableName, SchemaInspector.readTable(rootJdbc, dbName, tableName));
        }
        return tables;
    }

    @Test
    void freshProvisionMatchesCurrentManifestWithFullBaseline() {
        provisioner.createDatabase("baas_fresh1");
        provisioner.initSystemTables("baas_fresh1");

        Map<String, PhysicalTable> tables = readSystemTables("baas_fresh1");
        assertThat(SystemTableManifest.compare(tables)).isEqualTo(SystemTableManifest.MatchResult.MATCH_CURRENT);
        PhysicalTable users = tables.get("_users");
        assertThat(users.engine()).isEqualTo("InnoDB");
        assertThat(users.rowFormat()).isEqualTo("Dynamic");
        assertThat(users.collation()).isEqualTo("utf8mb4_general_ci");
        assertThat(users.findColumn("id").columnType()).isEqualTo("bigint");
        assertThat(users.findColumn("email").characterSet()).isEqualTo("utf8mb4");
    }

    @Test
    void planALegacyUnsignedTablesDetectedAndUpgradedInline() {
        provisioner.createDatabase("baas_legacy1");
        LegacySystemTables.create(rootJdbc, "baas_legacy1");
        assertThat(SystemTableManifest.compare(readSystemTables("baas_legacy1")))
            .isEqualTo(SystemTableManifest.MatchResult.MATCH_LEGACY);

        provisioner.initSystemTables("baas_legacy1");

        assertThat(SystemTableManifest.compare(readSystemTables("baas_legacy1")))
            .isEqualTo(SystemTableManifest.MatchResult.MATCH_CURRENT);
    }

    @Test
    void partiallyUpgradedLegacyTablesAreExplicitlyMixed() {
        provisioner.createDatabase("baas_mixed1");
        LegacySystemTables.create(rootJdbc, "baas_mixed1");
        rootJdbc.execute(SystemTableManifest.migrationSql("baas_mixed1", "_users", 1));

        assertThat(SystemTableManifest.compare(readSystemTables("baas_mixed1")))
            .isEqualTo(SystemTableManifest.MatchResult.MATCH_MIXED);
    }

    @Test
    void unknownDriftIsMismatchAndInitFails() {
        provisioner.createDatabase("baas_drift1");
        rootJdbc.execute("CREATE TABLE baas_drift1._users (id bigint NOT NULL AUTO_INCREMENT, "
                + "email varchar(255) NOT NULL, PRIMARY KEY (id)) ENGINE=InnoDB "
                + "DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC");

        assertThatThrownBy(() -> provisioner.initSystemTables("baas_drift1"))
            .isInstanceOf(IllegalStateException.class);
        Map<String, PhysicalTable> tables = readSystemTables("baas_drift1");
        assertThat(SystemTableManifest.compare(tables)).isEqualTo(SystemTableManifest.MatchResult.MISMATCH);
    }

    @Test
    void preexistingWrongCharsetDatabaseRejectedOnReadback() {
        rootJdbc.execute("CREATE DATABASE baas_badcs1 DEFAULT CHARACTER SET latin1");

        assertThatThrownBy(() -> provisioner.createDatabase("baas_badcs1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("baseline");
    }

    @Test
    void legacyUpgradeRejectedWhenUnsignedValueExceedsSignedRange() {
        provisioner.createDatabase("baas_over1");
        LegacySystemTables.create(rootJdbc, "baas_over1");
        rootJdbc.execute("INSERT INTO baas_over1._users (id, email, password_hash) "
                + "VALUES (18446744073709551615, 'x@y.z', 'h')");

        assertThatThrownBy(() -> provisioner.initSystemTables("baas_over1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("signed");
        assertThat(readSystemTables("baas_over1").get("_users").findColumn("id").columnType())
            .isEqualTo("bigint unsigned");
    }

    @Test
    void nonCanonicalDefaultIsManifestMismatch() {
        provisioner.createDatabase("baas_def1");
        provisioner.initSystemTables("baas_def1");
        rootJdbc.execute("ALTER TABLE baas_def1._sessions ALTER COLUMN status SET DEFAULT 'DISABLED'");

        assertThat(SystemTableManifest.compare(readSystemTables("baas_def1")))
            .isEqualTo(SystemTableManifest.MatchResult.MISMATCH);
    }

    @Test
    void stringDefaultCaseDriftIsManifestMismatch() {
        provisioner.createDatabase("baas_defcase1");
        provisioner.initSystemTables("baas_defcase1");
        rootJdbc.execute("ALTER TABLE baas_defcase1._sessions ALTER COLUMN status SET DEFAULT 'active'");

        assertThat(SystemTableManifest.compare(readSystemTables("baas_defcase1")))
            .isEqualTo(SystemTableManifest.MatchResult.MISMATCH);
    }

    @Test
    void unexpectedOnUpdateExtraIsManifestMismatch() {
        provisioner.createDatabase("baas_extra1");
        provisioner.initSystemTables("baas_extra1");
        rootJdbc.execute("ALTER TABLE baas_extra1._sessions MODIFY last_active_time datetime NOT NULL "
                + "DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP");

        assertThat(SystemTableManifest.compare(readSystemTables("baas_extra1")))
            .isEqualTo(SystemTableManifest.MatchResult.MISMATCH);
    }

    @Test
    void compositePrimaryKeyIsManifestMismatch() {
        provisioner.createDatabase("baas_pk1");
        provisioner.initSystemTables("baas_pk1");
        rootJdbc.execute("ALTER TABLE baas_pk1._users DROP PRIMARY KEY, ADD PRIMARY KEY (id, email)");

        assertThat(SystemTableManifest.compare(readSystemTables("baas_pk1")))
            .isEqualTo(SystemTableManifest.MatchResult.MISMATCH);
    }

    @Test
    void stringColumnCollationDriftIsManifestMismatch() {
        provisioner.createDatabase("baas_coll1");
        provisioner.initSystemTables("baas_coll1");
        rootJdbc.execute("ALTER TABLE baas_coll1._users MODIFY email varchar(255) CHARACTER SET utf8mb4 "
                + "COLLATE utf8mb4_bin NOT NULL");

        assertThat(SystemTableManifest.compare(readSystemTables("baas_coll1")))
            .isEqualTo(SystemTableManifest.MatchResult.MISMATCH);
    }

    @Test
    void legacyPhysicalBaselineDriftIsNotRecognizedAsKnownVersion() {
        provisioner.createDatabase("baas_legbase1");
        LegacySystemTables.create(rootJdbc, "baas_legbase1");
        rootJdbc.execute("ALTER TABLE baas_legbase1._sessions ROW_FORMAT=COMPACT");

        assertThat(SystemTableManifest.compare(readSystemTables("baas_legbase1")))
            .isEqualTo(SystemTableManifest.MatchResult.MISMATCH);
    }

}
