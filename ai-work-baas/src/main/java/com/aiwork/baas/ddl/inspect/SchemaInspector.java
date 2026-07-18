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

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * information_schema 读取器(spec §9.4):锁内以持锁连接读取,开通期以 Provisioner JdbcTemplate 读取。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public final class SchemaInspector {

    private SchemaInspector() {
    }

    /**
     * 用持锁物理连接构造 JdbcTemplate(suppressClose,不会关闭底层连接)。
     * @param connection 持锁连接
     * @return 不会关闭持锁连接的 JdbcTemplate
     */
    public static JdbcTemplate jdbcFor(Connection connection) {
        return new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    }

    public static PhysicalDatabase readDatabase(JdbcOperations jdbc, String dbName) {
        List<PhysicalDatabase> rows = jdbc.query(
                "SELECT DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME "
                        + "FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = ?",
                (rs, i) -> new PhysicalDatabase(rs.getString(1), rs.getString(2)), dbName);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public static boolean tableExists(JdbcOperations jdbc, String dbName, String tableName) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?", Long.class,
                dbName, tableName);
        return count != null && count > 0;
    }

    public static PhysicalTable readTable(JdbcOperations jdbc, String dbName, String tableName) {
        List<PhysicalTable> heads = readTableHeads(jdbc, dbName, tableName);
        if (heads.isEmpty()) {
            return null;
        }
        PhysicalTable head = heads.get(0);
        if (!"BASE TABLE".equals(head.tableType())) {
            return head;
        }
        return withDetails(jdbc, dbName, head);
    }

    /**
     * 全库表清单(含 VIEW 行,便于对账拒绝);BASE TABLE 补齐列与索引明细。
     * @return 所有物理表或视图
     */
    public static List<PhysicalTable> readAllTables(JdbcOperations jdbc, String dbName) {
        List<PhysicalTable> result = new ArrayList<>();
        for (PhysicalTable head : readTableHeads(jdbc, dbName, null)) {
            result.add("BASE TABLE".equals(head.tableType()) ? withDetails(jdbc, dbName, head) : head);
        }
        return result;
    }

    /**
     * spec §9.4 单列索引谓词。
     * @return 是否为可映射的单列二级索引
     */
    public static boolean isMappableSingleColumnIndex(PhysicalIndex index) {
        if (index.parts().size() != 1) {
            return false;
        }
        PhysicalIndex.Part part = index.parts().get(0);
        return part.columnName() != null && part.expression() == null && part.subPart() == null
                && "BTREE".equals(part.indexType()) && "YES".equals(part.visible()) && "A".equals(part.collation());
    }

    private static List<PhysicalTable> readTableHeads(JdbcOperations jdbc, String dbName, String tableName) {
        StringBuilder sql = new StringBuilder(
                "SELECT TABLE_NAME, TABLE_TYPE, ENGINE, ROW_FORMAT, TABLE_COLLATION, TABLE_COMMENT "
                        + "FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?");
        Object[] args = tableName == null ? new Object[] { dbName } : new Object[] { dbName, tableName };
        if (tableName != null) {
            sql.append(" AND TABLE_NAME = ?");
        }
        return jdbc.query(
                sql.toString(), (rs, i) -> new PhysicalTable(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), false, false, false, List.of(), List.of()),
                args);
    }

    private static PhysicalTable withDetails(JdbcOperations jdbc, String dbName, PhysicalTable head) {
        List<PhysicalColumn> columns = jdbc.query(
                "SELECT COLUMN_NAME, DATA_TYPE, COLUMN_TYPE, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, "
                        + "NUMERIC_SCALE, DATETIME_PRECISION, IS_NULLABLE, COLUMN_DEFAULT, EXTRA, "
                        + "CHARACTER_SET_NAME, COLLATION_NAME, COLUMN_KEY, COLUMN_COMMENT, GENERATION_EXPRESSION "
                        + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? "
                        + "ORDER BY ORDINAL_POSITION",
                (rs, i) -> new PhysicalColumn(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, Long.class), rs.getObject(5, Long.class), rs.getObject(6, Long.class),
                        rs.getObject(7, Long.class), "YES".equals(rs.getString(8)), rs.getString(9), rs.getString(10),
                        rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14), rs.getString(15)),
                dbName, head.tableName());

        Map<String, List<PhysicalIndex.Part>> partsByIndex = new LinkedHashMap<>();
        Map<String, Boolean> uniqueByIndex = new LinkedHashMap<>();
        jdbc.query("SELECT INDEX_NAME, NON_UNIQUE, COLUMN_NAME, EXPRESSION, SUB_PART, INDEX_TYPE, IS_VISIBLE, "
                + "COLLATION FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? "
                + "ORDER BY INDEX_NAME, SEQ_IN_INDEX", rs -> {
                    String indexName = rs.getString(1);
                    uniqueByIndex.put(indexName, rs.getLong(2) == 0);
                    partsByIndex.computeIfAbsent(indexName, key -> new ArrayList<>())
                        .add(new PhysicalIndex.Part(rs.getString(3), rs.getString(4), rs.getObject(5, Long.class),
                                rs.getString(6), rs.getString(7), rs.getString(8)));
                }, dbName, head.tableName());
        List<PhysicalIndex> indexes = partsByIndex.entrySet()
            .stream()
            .map(entry -> new PhysicalIndex(entry.getKey(), uniqueByIndex.get(entry.getKey()),
                    List.copyOf(entry.getValue())))
            .toList();

        boolean hasTriggers = count(jdbc, "SELECT COUNT(*) FROM information_schema.TRIGGERS "
                + "WHERE EVENT_OBJECT_SCHEMA = ? AND EVENT_OBJECT_TABLE = ?", dbName, head.tableName()) > 0;
        boolean hasForeignKeys = count(jdbc,
                "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND CONSTRAINT_TYPE = 'FOREIGN KEY'",
                dbName, head.tableName()) > 0;
        boolean hasChecks = count(jdbc,
                "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND CONSTRAINT_TYPE = 'CHECK'",
                dbName, head.tableName()) > 0;

        return new PhysicalTable(head.tableName(), head.tableType(), head.engine(), head.rowFormat(), head.collation(),
                head.tableComment(), hasTriggers, hasForeignKeys, hasChecks, columns, indexes);
    }

    private static long count(JdbcOperations jdbc, String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

}
