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

package com.aiwork.baas.ddl.inspect;

import com.aiwork.baas.ddl.type.ColumnType;
import com.aiwork.baas.ddl.type.LogicalColumn;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SchemaInspector information_schema 集成测试。
 *
 * @author ai-work
 * @date 2026/07/18
 */
@Testcontainers
class SchemaInspectorTest {

    private static final String DB = "inspect_db";

    @Container
    static MySQLContainer mysql = new MySQLContainer("mysql:8.4").withUsername("root").withPassword("root");

    static JdbcTemplate jdbc;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                mysql.getJdbcUrl().replace("/" + mysql.getDatabaseName(), "/mysql"), "root", "root");
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE DATABASE " + DB + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        jdbc.execute("CREATE TABLE " + DB + ".normal_table (" + "id bigint NOT NULL AUTO_INCREMENT,"
                + "email varchar(255) NOT NULL COMMENT 'mail'," + "age int DEFAULT 18,"
                + "vip boolean NOT NULL DEFAULT TRUE," + "note text," + "created datetime DEFAULT CURRENT_TIMESTAMP,"
                + "PRIMARY KEY (id), UNIQUE KEY uk_email (email), KEY idx_age (age)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC "
                + "COMMENT='normal comment'");
        jdbc.execute("CREATE TABLE " + DB + ".weird_table (" + "id bigint unsigned NOT NULL AUTO_INCREMENT,"
                + "flag tinyint(4) DEFAULT 0,"
                + "touched datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                + "micro datetime(6) DEFAULT NULL," + "gen int GENERATED ALWAYS AS (flag + 1) VIRTUAL,"
                + "name varchar(300)," + "PRIMARY KEY (id)," + "KEY idx_prefix (name(10)),"
                + "KEY idx_desc (flag DESC)," + "KEY idx_invisible (flag) INVISIBLE," + "FULLTEXT KEY ft_name (name),"
                + "KEY idx_func ((lower(name)))" + ") ENGINE=InnoDB");
        jdbc.execute("CREATE VIEW " + DB + ".a_view AS SELECT 1 AS x");
        jdbc.execute("CREATE TABLE " + DB + ".with_check (id bigint NOT NULL PRIMARY KEY, "
                + "n int CHECK (n > 0)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE " + DB + ".with_trigger (id bigint NOT NULL PRIMARY KEY, n int) ENGINE=InnoDB");
        jdbc.execute(
                "CREATE TRIGGER " + DB + ".trg BEFORE INSERT ON " + DB + ".with_trigger FOR EACH ROW SET NEW.n = 1");
    }

    @Test
    void readsDatabaseBaseline() {
        PhysicalDatabase database = SchemaInspector.readDatabase(jdbc, DB);
        assertThat(database.charset()).isEqualTo("utf8mb4");
        assertThat(database.collation()).isEqualTo("utf8mb4_general_ci");
    }

    @Test
    void readsTableWithColumnsAndIndexes() {
        PhysicalTable table = SchemaInspector.readTable(jdbc, DB, "normal_table");
        assertThat(table.engine()).isEqualTo("InnoDB");
        assertThat(table.rowFormat()).isEqualTo("Dynamic");
        assertThat(table.collation()).isEqualTo("utf8mb4_general_ci");
        assertThat(table.tableComment()).isEqualTo("normal comment");
        assertThat(table.columns()).hasSize(6);
        assertThat(table.secondaryIndexes()).extracting(PhysicalIndex::indexName)
            .containsExactlyInAnyOrder("uk_email", "idx_age");
        assertThat(table.hasTriggers()).isFalse();
        assertThat(table.hasCheckConstraints()).isFalse();
        assertThat(SchemaInspector.readTable(jdbc, DB, "no_such_table")).isNull();
    }

    @Test
    void mapsWhitelistColumnsToLogicalModel() {
        PhysicalTable table = SchemaInspector.readTable(jdbc, DB, "normal_table");

        LogicalColumn email = LogicalModelMapper.toLogical(table.findColumn("email"), true, false).value();
        assertThat(email.type()).isEqualTo(ColumnType.VARCHAR);
        assertThat(email.length()).isEqualTo(255);
        assertThat(email.nullable()).isFalse();
        assertThat(email.comment()).isEqualTo("mail");

        LogicalColumn age = LogicalModelMapper.toLogical(table.findColumn("age"), false, true).value();
        assertThat(age.type()).isEqualTo(ColumnType.INT);
        assertThat(age.length()).isNull();
        assertThat(age.defaultValue()).isEqualTo("18");

        LogicalColumn vip = LogicalModelMapper.toLogical(table.findColumn("vip"), false, false).value();
        assertThat(vip.type()).isEqualTo(ColumnType.BOOLEAN);
        assertThat(vip.defaultValue()).isEqualTo("true");

        LogicalColumn created = LogicalModelMapper.toLogical(table.findColumn("created"), false, false).value();
        assertThat(created.type()).isEqualTo(ColumnType.DATETIME);
        assertThat(created.defaultValue()).isEqualTo("CURRENT_TIMESTAMP");

        LogicalColumn id = LogicalModelMapper.toLogical(table.findColumn("id"), false, false).value();
        assertThat(id.pk()).isTrue();
        assertThat(id.autoIncrement()).isTrue();
        assertThat(id.type()).isEqualTo(ColumnType.BIGINT);
    }

    @Test
    void rejectsNonMappableColumnVariants() {
        PhysicalTable table = SchemaInspector.readTable(jdbc, DB, "weird_table");

        assertThat(LogicalModelMapper.toLogical(table.findColumn("id"), false, false).ok()).isFalse();
        assertThat(LogicalModelMapper.toLogical(table.findColumn("flag"), false, false).ok()).isFalse();
        assertThat(LogicalModelMapper.toLogical(table.findColumn("touched"), false, false).ok()).isFalse();
        assertThat(LogicalModelMapper.toLogical(table.findColumn("micro"), false, false).ok()).isFalse();
        assertThat(LogicalModelMapper.toLogical(table.findColumn("gen"), false, false).ok()).isFalse();
        assertThat(LogicalModelMapper.toLogical(table.findColumn("name"), false, false).ok()).isTrue();
    }

    @Test
    void displayWidthIntStillMapsToInt() {
        jdbc.execute("CREATE TABLE " + DB + ".legacy_width (id bigint NOT NULL PRIMARY KEY, n int) ENGINE=InnoDB");
        PhysicalTable table = SchemaInspector.readTable(jdbc, DB, "legacy_width");
        MappingOutcome<LogicalColumn> outcome = LogicalModelMapper.toLogical(table.findColumn("n"), false, false);
        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.value().type()).isEqualTo(ColumnType.INT);
    }

    @Test
    void singleColumnIndexPredicatePerSpec() {
        PhysicalTable table = SchemaInspector.readTable(jdbc, DB, "weird_table");
        for (PhysicalIndex index : table.secondaryIndexes()) {
            assertThat(SchemaInspector.isMappableSingleColumnIndex(index))
                .as("index %s must be rejected", index.indexName())
                .isFalse();
        }
        PhysicalTable normal = SchemaInspector.readTable(jdbc, DB, "normal_table");
        assertThat(normal.mappableSingleColumnIndexOn("email")).isNotNull();
        assertThat(normal.mappableSingleColumnIndexOn("email").unique()).isTrue();
        assertThat(normal.mappableSingleColumnIndexOn("age").unique()).isFalse();
        assertThat(normal.mappableSingleColumnIndexOn("note")).isNull();
    }

    @Test
    void detectsChecksTriggersAndViews() {
        assertThat(SchemaInspector.readTable(jdbc, DB, "with_check").hasCheckConstraints()).isTrue();
        assertThat(SchemaInspector.readTable(jdbc, DB, "with_trigger").hasTriggers()).isTrue();
        assertThat(SchemaInspector.readAllTables(jdbc, DB)).anySatisfy(t -> {
            assertThat(t.tableName()).isEqualTo("a_view");
            assertThat(t.tableType()).isEqualTo("VIEW");
        });
    }

}
