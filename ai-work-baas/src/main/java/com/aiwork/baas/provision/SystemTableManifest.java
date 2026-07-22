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

import com.aiwork.baas.ddl.inspect.PhysicalIndex;
import com.aiwork.baas.ddl.inspect.PhysicalTable;
import com.aiwork.baas.ddl.inspect.SchemaInspector;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 版本化系统表 manifest(spec §9.1)：v1/v2/v3 三版本链——v1 = Plan A unsigned；
 * v2 = signed bigint + 显式基线；v3 = v2 + `_users.deleted_at`(软删除)。覆盖列集合、
 * 类型及 signedness、NULL/default/EXTRA、精确 PRIMARY/自增、二级索引形状与列/表物理基线。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public final class SystemTableManifest {

    public enum MatchResult {

        MATCH_CURRENT, MATCH_LEGACY, MATCH_MIXED, MISMATCH

    }

    /** 当前系统表 manifest 版本(spec §9.1:v3 = v2 + _users.deleted_at)。 */
    public static final int CURRENT_VERSION = 3;

    private static final List<Integer> KNOWN_VERSIONS = List.of(1, 2, 3);

    public static final List<String> SYSTEM_TABLE_NAMES = List.of("_users", "_sessions", "_refresh_tokens");

    private record ExpectedColumn(String name, String columnType, boolean nullable, boolean autoIncrement) {
    }

    private record ExpectedIndex(String name, boolean unique, String column) {
    }

    // v1 = Plan A unsigned;v2 = signed;v3 = v2 + _users.deleted_at(datetime NULL 无默认值)
    private static final Map<Integer, Map<String, List<ExpectedColumn>>> COLUMNS_BY_VERSION = buildColumns();

    private static Map<Integer, Map<String, List<ExpectedColumn>>> buildColumns() {
        Map<String, List<ExpectedColumn>> v1 = Map.of(
                "_users", List.of(
                        new ExpectedColumn("id", "bigint unsigned", false, true),
                        new ExpectedColumn("email", "varchar(255)", false, false),
                        new ExpectedColumn("password_hash", "varchar(100)", false, false),
                        new ExpectedColumn("raw_meta", "json", true, false),
                        new ExpectedColumn("create_time", "datetime", false, false),
                        new ExpectedColumn("update_time", "datetime", false, false)),
                "_sessions", List.of(
                        new ExpectedColumn("id", "bigint unsigned", false, true),
                        new ExpectedColumn("user_id", "bigint unsigned", false, false),
                        new ExpectedColumn("status", "varchar(16)", false, false),
                        new ExpectedColumn("create_time", "datetime", false, false),
                        new ExpectedColumn("update_time", "datetime", false, false),
                        new ExpectedColumn("last_active_time", "datetime", false, false)),
                "_refresh_tokens", List.of(
                        new ExpectedColumn("id", "bigint unsigned", false, true),
                        new ExpectedColumn("token_hash", "char(64)", false, false),
                        new ExpectedColumn("session_id", "bigint unsigned", false, false),
                        new ExpectedColumn("expire_time", "datetime", false, false),
                        new ExpectedColumn("consumed_at", "datetime", true, false),
                        new ExpectedColumn("replacement_token_id", "bigint unsigned", true, false),
                        new ExpectedColumn("reuse_grace_until", "datetime", true, false),
                        new ExpectedColumn("replay_payload_ciphertext", "text", true, false),
                        new ExpectedColumn("create_time", "datetime", false, false),
                        new ExpectedColumn("update_time", "datetime", false, false)));
        Map<String, List<ExpectedColumn>> v2 = Map.of(
                "_users", v1.get("_users").stream()
                    .map(SystemTableManifest::signed).toList(),
                "_sessions", v1.get("_sessions").stream()
                    .map(SystemTableManifest::signed).toList(),
                "_refresh_tokens", v1.get("_refresh_tokens").stream()
                    .map(SystemTableManifest::signed).toList());
        List<ExpectedColumn> v3Users = new ArrayList<>(v2.get("_users"));
        v3Users.add(new ExpectedColumn("deleted_at", "datetime", true, false));
        Map<String, List<ExpectedColumn>> v3 = Map.of(
                "_users", List.copyOf(v3Users),
                "_sessions", v2.get("_sessions"),
                "_refresh_tokens", v2.get("_refresh_tokens"));
        return Map.of(1, v1, 2, v2, 3, v3);
    }

    private static ExpectedColumn signed(ExpectedColumn column) {
        return "bigint unsigned".equals(column.columnType())
                ? new ExpectedColumn(column.name(), "bigint", column.nullable(), column.autoIncrement())
                : column;
    }

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
        List<Set<Integer>> versionSets = new ArrayList<>();
        for (String tableName : SYSTEM_TABLE_NAMES) {
            Set<Integer> versions = matchedVersions(tableName, tables.get(tableName));
            if (versions.isEmpty()) {
                return MatchResult.MISMATCH;
            }
            versionSets.add(versions);
        }
        if (versionSets.stream().allMatch(set -> set.contains(CURRENT_VERSION))) {
            return MatchResult.MATCH_CURRENT;
        }
        for (int legacy = CURRENT_VERSION - 1; legacy >= 1; legacy--) {
            int candidate = legacy;
            if (versionSets.stream().allMatch(set -> set.contains(candidate))) {
                return MatchResult.MATCH_LEGACY;
            }
        }
        return MatchResult.MATCH_MIXED;
    }

    /**
     * 某物理表匹配的全部已知 manifest 版本(可能不止一个,例如 v2 与 v3 结构相同的表)。
     * @param tableName 系统表名
     * @param table 物理表快照
     * @return 匹配的版本集合,均不匹配则为空集合
     */
    public static Set<Integer> matchedVersions(String tableName, PhysicalTable table) {
        Set<Integer> versions = new LinkedHashSet<>();
        for (int version : KNOWN_VERSIONS) {
            if (tableMatches(tableName, table, version)) {
                versions.add(version);
            }
        }
        return versions;
    }

    /**
     * 单表精确比对：列集合/COLUMN_TYPE/可空/自增、update_time 的 on update、
     * 二级索引形状(名称+唯一性+列)、主键为 (id)、ENGINE=InnoDB 与字符集物理基线。
     * @param tableName 系统表名
     * @param table 物理表快照
     * @param version 待校验的 manifest 版本(1/2/3)
     * @return 是否符合指定版本的完整结构
     */
    public static boolean tableMatches(String tableName, PhysicalTable table, int version) {
        List<ExpectedColumn> expectedColumns = COLUMNS_BY_VERSION.get(version) == null ? null
                : COLUMNS_BY_VERSION.get(version).get(tableName);
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
            String expectedType = expected.columnType();
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
     * 单表从 fromVersion 升级到当前版的 ALTER SQL。fromVersion=1 时一次性 signed 化 +
     * 补物理基线 + (仅 _users)加 deleted_at；fromVersion=2 时仅 _users 补 deleted_at
     * (_sessions/_refresh_tokens 的 v2 与 v3 结构相同,不存在该迁移)。
     * @param dbName 项目库名
     * @param tableName 系统表名
     * @param fromVersion 迁移起点版本(1 或 2)
     * @return 单表 ALTER SQL
     */
    public static String migrationSql(String dbName, String tableName, int fromVersion) {
        if (fromVersion == 2) {
            if (!"_users".equals(tableName)) {
                throw new IllegalArgumentException("v2 -> v3 only alters _users: " + tableName);
            }
            return "ALTER TABLE `%s`.`_users` ADD COLUMN deleted_at datetime DEFAULT NULL"
                .formatted(dbName);
        }
        if (fromVersion != 1) {
            throw new IllegalArgumentException("unknown migration source version: " + fromVersion);
        }
        String modifies = switch (tableName) {
            case "_users" -> "MODIFY id bigint NOT NULL AUTO_INCREMENT, "
                    + "ADD COLUMN deleted_at datetime DEFAULT NULL";
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
