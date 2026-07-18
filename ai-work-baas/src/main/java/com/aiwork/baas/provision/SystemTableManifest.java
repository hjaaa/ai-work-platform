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

import com.aiwork.baas.ddl.inspect.PhysicalIndex;
import com.aiwork.baas.ddl.inspect.PhysicalTable;
import com.aiwork.baas.ddl.inspect.SchemaInspector;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 版本化系统表 manifest(spec §9.1)：覆盖列集合、类型及 signedness、NULL/default/EXTRA、
 * 精确 PRIMARY/自增、二级索引形状与列/表物理基线。当前版 = signed bigint + 显式基线；
 * 遗留版 = Plan A unsigned。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public final class SystemTableManifest {

    public enum MatchResult {

        MATCH_CURRENT, MATCH_LEGACY_PLAN_A, MISMATCH

    }

    public static final List<String> SYSTEM_TABLE_NAMES = List.of("_users", "_sessions", "_refresh_tokens");

    private record ExpectedColumn(String name, String currentType, String legacyType, boolean nullable,
            boolean autoIncrement) {
    }

    private record ExpectedIndex(String name, boolean unique, String column) {
    }

    private static final Map<String, List<ExpectedColumn>> COLUMNS = Map.of(
            "_users", List.of(
                    new ExpectedColumn("id", "bigint", "bigint unsigned", false, true),
                    new ExpectedColumn("email", "varchar(255)", "varchar(255)", false, false),
                    new ExpectedColumn("password_hash", "varchar(100)", "varchar(100)", false, false),
                    new ExpectedColumn("raw_meta", "json", "json", true, false),
                    new ExpectedColumn("create_time", "datetime", "datetime", false, false),
                    new ExpectedColumn("update_time", "datetime", "datetime", false, false)),
            "_sessions", List.of(
                    new ExpectedColumn("id", "bigint", "bigint unsigned", false, true),
                    new ExpectedColumn("user_id", "bigint", "bigint unsigned", false, false),
                    new ExpectedColumn("status", "varchar(16)", "varchar(16)", false, false),
                    new ExpectedColumn("create_time", "datetime", "datetime", false, false),
                    new ExpectedColumn("update_time", "datetime", "datetime", false, false),
                    new ExpectedColumn("last_active_time", "datetime", "datetime", false, false)),
            "_refresh_tokens", List.of(
                    new ExpectedColumn("id", "bigint", "bigint unsigned", false, true),
                    new ExpectedColumn("token_hash", "char(64)", "char(64)", false, false),
                    new ExpectedColumn("session_id", "bigint", "bigint unsigned", false, false),
                    new ExpectedColumn("expire_time", "datetime", "datetime", false, false),
                    new ExpectedColumn("consumed_at", "datetime", "datetime", true, false),
                    new ExpectedColumn("replacement_token_id", "bigint", "bigint unsigned", true, false),
                    new ExpectedColumn("reuse_grace_until", "datetime", "datetime", true, false),
                    new ExpectedColumn("replay_payload_ciphertext", "text", "text", true, false),
                    new ExpectedColumn("create_time", "datetime", "datetime", false, false),
                    new ExpectedColumn("update_time", "datetime", "datetime", false, false)));

    private static final Map<String, List<ExpectedIndex>> INDEXES = Map.of(
            "_users", List.of(new ExpectedIndex("uk_email", true, "email")),
            "_sessions", List.of(new ExpectedIndex("idx_user", false, "user_id")),
            "_refresh_tokens", List.of(new ExpectedIndex("uk_token_hash", true, "token_hash"),
                    new ExpectedIndex("idx_session", false, "session_id")));

    private static final Map<String, Map<String, String>> DEFAULTS = Map.of(
            "_users", Map.of("create_time", "CURRENT_TIMESTAMP", "update_time", "CURRENT_TIMESTAMP"),
            "_sessions", Map.of("status", "ACTIVE", "create_time", "CURRENT_TIMESTAMP",
                    "update_time", "CURRENT_TIMESTAMP", "last_active_time", "CURRENT_TIMESTAMP"),
            "_refresh_tokens", Map.of("create_time", "CURRENT_TIMESTAMP", "update_time", "CURRENT_TIMESTAMP"));

    private static final Map<String, List<String>> UNSIGNED_COLUMNS = Map.of(
            "_users", List.of("id"),
            "_sessions", List.of("id", "user_id"),
            "_refresh_tokens", List.of("id", "session_id", "replacement_token_id"));

    private SystemTableManifest() {
    }

    public static MatchResult compare(Map<String, PhysicalTable> tables) {
        boolean anyLegacy = false;
        for (String tableName : SYSTEM_TABLE_NAMES) {
            PhysicalTable table = tables.get(tableName);
            if (tableMatches(tableName, table, false)) {
                continue;
            }
            if (tableMatches(tableName, table, true)) {
                anyLegacy = true;
                continue;
            }
            return MatchResult.MISMATCH;
        }
        return anyLegacy ? MatchResult.MATCH_LEGACY_PLAN_A : MatchResult.MATCH_CURRENT;
    }

    /**
     * 单表精确比对：列集合/COLUMN_TYPE/可空/自增、update_time 的 on update、
     * 二级索引形状(名称+唯一性+列)、主键为 (id)、ENGINE=InnoDB 与字符集物理基线。
     * @param tableName 系统表名
     * @param table 物理表快照
     * @param legacy 是否按 Plan A 遗留版本校验
     * @return 是否符合指定版本的完整结构
     */
    public static boolean tableMatches(String tableName, PhysicalTable table, boolean legacy) {
        List<ExpectedColumn> expectedColumns = COLUMNS.get(tableName);
        if (expectedColumns == null || table == null || !"BASE TABLE".equals(table.tableType())
                || !"InnoDB".equals(table.engine()) || !"utf8mb4_general_ci".equals(table.collation())
                || !"Dynamic".equals(table.rowFormat()) || table.hasTriggers() || table.hasForeignKeys()
                || table.hasCheckConstraints() || table.columns().size() != expectedColumns.size()) {
            return false;
        }
        for (ExpectedColumn expected : expectedColumns) {
            var column = table.findColumn(expected.name());
            if (column == null) {
                return false;
            }
            String expectedType = legacy ? expected.legacyType() : expected.currentType();
            if (!expectedType.equals(column.columnType().toLowerCase(Locale.ROOT))
                    || column.nullable() != expected.nullable() || column.isAutoIncrement() != expected.autoIncrement()) {
                return false;
            }
            String expectedDefault = DEFAULTS.get(tableName).get(expected.name());
            String actualDefault = normalizeDefault(expectedDefault, column.columnDefault());
            if (!Objects.equals(expectedDefault, actualDefault)) {
                return false;
            }
            String extra = column.extra() == null ? "" : column.extra().toLowerCase(Locale.ROOT).trim();
            boolean matchesExtra = expected.autoIncrement() ? "auto_increment".equals(extra)
                    : "update_time".equals(expected.name())
                            ? extra.equals("on update current_timestamp")
                                    || extra.equals("default_generated on update current_timestamp")
                            : "CURRENT_TIMESTAMP".equals(expectedDefault)
                                    ? extra.isEmpty() || "default_generated".equals(extra)
                                    : extra.isEmpty();
            if (!matchesExtra) {
                return false;
            }
        }
        List<PhysicalIndex> primaryIndexes = table.indexes()
            .stream()
            .filter(index -> "PRIMARY".equals(index.indexName()))
            .toList();
        if (primaryIndexes.size() != 1 || primaryIndexes.get(0).parts().size() != 1
                || !"id".equals(primaryIndexes.get(0).parts().get(0).columnName())) {
            return false;
        }
        Set<String> actualIndexes = table.secondaryIndexes()
            .stream()
            .filter(SchemaInspector::isMappableSingleColumnIndex)
            .map(index -> index.indexName() + "|" + index.unique() + "|" + index.parts().get(0).columnName())
            .collect(Collectors.toSet());
        Set<String> expectedIndexes = INDEXES.get(tableName)
            .stream()
            .map(index -> index.name() + "|" + index.unique() + "|" + index.column())
            .collect(Collectors.toSet());
        if (!actualIndexes.equals(expectedIndexes) || table.secondaryIndexes().size() != expectedIndexes.size()) {
            return false;
        }
        for (var column : table.columns()) {
            if (column.characterSet() != null && !"utf8mb4".equals(column.characterSet())) {
                return false;
            }
            if (column.collation() != null && !"utf8mb4_general_ci".equals(column.collation())) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeDefault(String expectedDefault, String actualDefault) {
        if (actualDefault == null || !"CURRENT_TIMESTAMP".equals(expectedDefault)) {
            return actualDefault;
        }
        String normalized = actualDefault.toUpperCase(Locale.ROOT).replace("()", "");
        return "CURRENT_TIMESTAMP".equals(normalized) ? normalized : actualDefault;
    }

    /**
     * 遗留 → 当前的单表升级 ALTER(signed 化 + 补物理基线)。
     * @param dbName 项目库名
     * @param tableName 系统表名
     * @return 单表 ALTER SQL
     */
    public static String legacyMigrationSql(String dbName, String tableName) {
        String modifies = switch (tableName) {
            case "_users" -> "MODIFY id bigint NOT NULL AUTO_INCREMENT";
            case "_sessions" -> "MODIFY id bigint NOT NULL AUTO_INCREMENT, MODIFY user_id bigint NOT NULL";
            case "_refresh_tokens" -> "MODIFY id bigint NOT NULL AUTO_INCREMENT, "
                    + "MODIFY session_id bigint NOT NULL, MODIFY replacement_token_id bigint DEFAULT NULL";
            default -> throw new IllegalArgumentException("unknown system table: " + tableName);
        };
        return "ALTER TABLE `%s`.`%s` %s, ENGINE=InnoDB, ROW_FORMAT=DYNAMIC, "
            .formatted(dbName, tableName, modifies)
                + "CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci";
    }

    /**
     * 返回超出 signed bigint 上限的行数(0 即可安全迁移)。
     * @param dbName 项目库名
     * @param tableName 系统表名
     * @return 检查超界值数量的 SQL
     */
    public static String unsignedBoundsCheckSql(String dbName, String tableName) {
        List<String> unsignedColumns = UNSIGNED_COLUMNS.get(tableName);
        if (unsignedColumns == null) {
            throw new IllegalArgumentException("unknown system table: " + tableName);
        }
        String predicate = unsignedColumns.stream()
            .map(column -> column + " > 9223372036854775807")
            .collect(Collectors.joining(" OR "));
        return "SELECT COUNT(*) FROM `%s`.`%s` WHERE %s".formatted(dbName, tableName, predicate);
    }

}
