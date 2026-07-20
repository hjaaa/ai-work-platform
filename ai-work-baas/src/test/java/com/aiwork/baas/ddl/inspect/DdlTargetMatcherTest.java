/*
 *
 *      Copyright (c) 2018-2026, lengleng All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice,
 *  this list of conditions and the following disclaimer.
 *  Redistributions in binary form must reproduce the above copyright
 *  notice, this list of conditions and the following disclaimer in the
 *  documentation and/or other materials provided with the distribution.
 *  Neither the name of the pig4cloud.com developer nor the names of its
 *  contributors may be used to endorse or promote products derived from
 *  this software without specific prior written permission.
 *  Author: lengleng (wangiegie@gmail.com)
 *
 */

package com.aiwork.baas.ddl.inspect;

import com.aiwork.baas.ddl.type.ColumnType;
import com.aiwork.baas.ddl.type.LogicalColumn;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DdlTargetMatcherTest {

    private static final LogicalColumn ID = new LogicalColumn("id", ColumnType.BIGINT, null, null, false, null,
            true, true, false, false, null);

    private static final LogicalColumn EMAIL = new LogicalColumn("email", ColumnType.VARCHAR, 64, null, true,
            "a@example.com", false, false, true, false, "邮箱");

    @Test
    void exactNormalizedTargetMatches() {
        assertThat(DdlTargetMatcher.matches(table("demo", "表注释", true, true), "demo", "表注释",
                List.of(ID, EMAIL))).isTrue();
    }

    @Test
    void nullableAndTableCommentDifferencesDoNotMatchButNonCanonicalIndexNameCanMatch() {
        assertThat(DdlTargetMatcher.matches(table("demo", "旧注释", true, true), "demo", "表注释",
                List.of(ID, EMAIL))).isFalse();
        assertThat(DdlTargetMatcher.matches(table("demo", "表注释", false, true), "demo", "表注释",
                List.of(ID, EMAIL))).isFalse();
        assertThat(DdlTargetMatcher.matches(table("demo", "表注释", true, false), "demo", "表注释",
                List.of(ID, EMAIL))).isTrue();
    }

    @Test
    void varcharAndTextColumnsRequireUtf8mb4Baseline() {
        PhysicalColumn text = new PhysicalColumn("note", "text", "text", null, null, null, null, true, null, "",
                "utf8mb4", "utf8mb4_general_ci", "", "", "");
        PhysicalTable wrongCharset = table("demo", "表注释", true, List.of(
                new PhysicalColumn("id", "bigint", "bigint", null, 19L, 0L, null, false, null, "auto_increment",
                        null, null, "PRI", "", ""),
                new PhysicalColumn("email", "varchar", "varchar(64)", 64L, null, null, null, true, "a@example.com",
                        "", "utf8", "utf8_general_ci", "UNI", "邮箱", ""), text));
        assertThat(DdlTargetMatcher.matches(wrongCharset, "demo", "表注释",
                List.of(ID, EMAIL, new LogicalColumn("note", ColumnType.TEXT, null, null, true, null, false, false,
                        false, false, null)))).isFalse();
    }

    @Test
    void roundTripNormalizesUniqueIndexAndBlankComments() {
        LogicalColumn normalized = new LogicalColumn("email", ColumnType.VARCHAR, 64, null, true, "a@example.com",
                false, false, true, true, "");
        assertThat(normalized.indexed()).isFalse();
        assertThat(normalized.comment()).isNull();

        PhysicalColumn id = new PhysicalColumn("id", "bigint", "bigint", null, 19L, 0L, null, false, null,
                "auto_increment", null, null, "PRI", "", "");
        PhysicalColumn email = new PhysicalColumn("email", "varchar", "varchar(64)", 64L, null, null, null, true,
                "a@example.com", "", "utf8mb4", "utf8mb4_general_ci", "UNI", "", "");
        PhysicalTable actual = new PhysicalTable("demo", "BASE TABLE", "InnoDB", "Dynamic", "utf8mb4_general_ci",
                null, false, false, false, List.of(id, email), List.of(
                        new PhysicalIndex("PRIMARY", true,
                                List.of(new PhysicalIndex.Part("id", null, null, "BTREE", "YES", "A"))),
                        new PhysicalIndex("foreign_name", true,
                                List.of(new PhysicalIndex.Part("email", null, null, "BTREE", "YES", "A")))));
        assertThat(DdlTargetMatcher.matches(actual, "demo", "", List.of(ID, normalized))).isTrue();
    }

    @Test
    void descendingPrimaryKeyDoesNotMatchManagedTarget() {
        PhysicalTable actual = table("demo", "表注释", true, true);
        PhysicalIndex descending = new PhysicalIndex("PRIMARY", true,
                List.of(new PhysicalIndex.Part("id", null, null, "BTREE", "YES", "D")));
        PhysicalTable drifted = new PhysicalTable(actual.tableName(), actual.tableType(), actual.engine(),
                actual.rowFormat(), actual.collation(), actual.tableComment(), actual.hasTriggers(),
                actual.hasForeignKeys(), actual.hasCheckConstraints(), actual.columns(),
                List.of(descending, actual.secondaryIndexes().get(0)));

        assertThat(DdlTargetMatcher.matches(drifted, "demo", "表注释", List.of(ID, EMAIL))).isFalse();
    }

    private PhysicalTable table(String name, String comment, boolean nullable, boolean canonicalIndexName) {
        return table(name, comment, canonicalIndexName, List.of(
                new PhysicalColumn("id", "bigint", "bigint", null, 19L, 0L, null, false, null, "auto_increment",
                        null, null, "PRI", "", ""),
                new PhysicalColumn("email", "varchar", "varchar(64)", 64L, null, null, null, nullable,
                        "a@example.com", "", "utf8mb4", "utf8mb4_general_ci", "UNI", "邮箱", "")));
    }

    private PhysicalTable table(String name, String comment, boolean canonicalIndexName, List<PhysicalColumn> columns) {
        PhysicalIndex primary = new PhysicalIndex("PRIMARY", true,
                List.of(new PhysicalIndex.Part("id", null, null, "BTREE", "YES", "A")));
        PhysicalIndex unique = new PhysicalIndex(canonicalIndexName ? "uk_email" : "foo_email", true,
                List.of(new PhysicalIndex.Part("email", null, null, "BTREE", "YES", "A")));
        return new PhysicalTable(name, "BASE TABLE", "InnoDB", "Dynamic", "utf8mb4_general_ci", comment,
                false, false, false, columns, List.of(primary, unique));
    }

}
