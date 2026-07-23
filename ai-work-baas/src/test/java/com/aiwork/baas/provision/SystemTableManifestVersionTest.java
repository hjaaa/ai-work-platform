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
import com.aiwork.baas.support.PlanBContainerSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * manifest 三版本链判定(spec §9.1 v1/v2/v3)与混部回归(spec §14)。
 */
class SystemTableManifestVersionTest extends PlanBContainerSupport {

    static JdbcTemplate jdbc;

    @BeforeAll
    static void setUp() {
        jdbc = new JdbcTemplate(mysqlDataSource());
    }

    private static void createDb(String db) {
        jdbc.execute("CREATE DATABASE IF NOT EXISTS `" + db
                + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
    }

    /** 按 ProjectProvisioner 当前模板建 v3 三表。 */
    private static void createV3(String db) {
        createDb(db);
        new ProjectProvisioner(mysqlDataSource()).initSystemTables(db);
    }

    /** v2 = v3 去掉 _users.deleted_at。 */
    private static void createV2(String db) {
        createV3(db);
        jdbc.execute("ALTER TABLE `" + db + "`.`_users` DROP COLUMN deleted_at");
    }

    /** v1 = Plan A unsigned,复用既有测试助手。 */
    private static void createV1(String db) {
        createDb(db);
        LegacySystemTables.create(jdbc, db);
    }

    private static Map<String, PhysicalTable> read(String db) {
        Map<String, PhysicalTable> tables = new HashMap<>();
        for (String name : SystemTableManifest.SYSTEM_TABLE_NAMES) {
            tables.put(name, SchemaInspector.readTable(jdbc, db, name));
        }
        return tables;
    }

    @Test
    void v3MatchesCurrent() {
        createV3("mfst_v3");
        assertThat(SystemTableManifest.compare(read("mfst_v3")))
            .isEqualTo(SystemTableManifest.MatchResult.MATCH_CURRENT);
        assertThat(SystemTableManifest.matchedVersions("_users",
                SchemaInspector.readTable(jdbc, "mfst_v3", "_users"))).containsExactly(3);
        // _sessions 的 v2 与 v3 结构相同,两版本都匹配
        assertThat(SystemTableManifest.matchedVersions("_sessions",
                SchemaInspector.readTable(jdbc, "mfst_v3", "_sessions"))).contains(2, 3);
    }

    @Test
    void v2MatchesLegacy() {
        createV2("mfst_v2");
        assertThat(SystemTableManifest.compare(read("mfst_v2")))
            .isEqualTo(SystemTableManifest.MatchResult.MATCH_LEGACY);
    }

    @Test
    void v1MatchesLegacy() {
        createV1("mfst_v1");
        assertThat(SystemTableManifest.compare(read("mfst_v1")))
            .isEqualTo(SystemTableManifest.MatchResult.MATCH_LEGACY);
    }

    @Test
    void partialMigrationIsMixed() {
        // _users 已迁 v3、其余仍 v1 → 部分迁移,可续跑
        createV1("mfst_mixed");
        jdbc.execute(SystemTableManifest.migrationSql("mfst_mixed", "_users", 1));
        assertThat(SystemTableManifest.compare(read("mfst_mixed")))
            .isEqualTo(SystemTableManifest.MatchResult.MATCH_MIXED);
    }

    @Test
    void unknownColumnIsMismatch() {
        createV3("mfst_bad");
        jdbc.execute("ALTER TABLE `mfst_bad`.`_users` ADD COLUMN rogue int NULL");
        assertThat(SystemTableManifest.compare(read("mfst_bad")))
            .isEqualTo(SystemTableManifest.MatchResult.MISMATCH);
    }

    /** 混部回归(spec §14):v2 比对逻辑(即 tableMatches(…, 2))对 v3 物理结构必须不匹配——
        固化「旧实例扫描已迁 v3 项目会判 MISMATCH」的危害证据,支撑 §9.1 停服发布协议。 */
    @Test
    void v2LogicRejectsV3Structure() {
        createV3("mfst_mixdeploy");
        PhysicalTable users = SchemaInspector.readTable(jdbc, "mfst_mixdeploy", "_users");
        assertThat(SystemTableManifest.tableMatches("_users", users, 2)).isFalse();
        assertThat(SystemTableManifest.tableMatches("_users", users, 3)).isTrue();
    }

    @Test
    void migrationSqlPerVersion() {
        assertThat(SystemTableManifest.migrationSql("d", "_users", 2))
            .isEqualTo("ALTER TABLE `d`.`_users` ADD COLUMN deleted_at datetime DEFAULT NULL");
        assertThat(SystemTableManifest.migrationSql("d", "_users", 1))
            .contains("MODIFY id bigint NOT NULL AUTO_INCREMENT")
            .contains("ADD COLUMN deleted_at datetime DEFAULT NULL")
            .contains("CONVERT TO CHARACTER SET utf8mb4");
        // v2 的 _sessions/_refresh_tokens 与 v3 相同,不存在 from=2 的迁移语句
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> SystemTableManifest.migrationSql("d", "_sessions", 2));
    }

}
