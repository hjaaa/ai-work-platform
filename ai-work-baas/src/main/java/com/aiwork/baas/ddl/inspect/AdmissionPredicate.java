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

import com.aiwork.baas.ddl.index.IndexAdmission;
import com.aiwork.baas.ddl.type.LogicalColumn;
import com.aiwork.baas.exception.BaasBadRequestException;
import com.aiwork.baas.provision.IdentifierValidator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ACTIVE 准入谓词(spec §9.4):导入、按库修正、CONFLICT 恢复三条路径统一校验。
 *
 * @author ai-work
 * @date 2026/07/19
 */
public final class AdmissionPredicate {

    private static final String BASE_TABLE = "BASE TABLE";

    private static final String REQUIRED_ENGINE = "InnoDB";

    private static final String REQUIRED_CHARSET = "utf8mb4";

    private static final String REQUIRED_COLLATION = "utf8mb4_general_ci";

    private static final String REQUIRED_ROW_FORMAT = "Dynamic";

    private static final String PRIMARY_INDEX_NAME = "PRIMARY";

    private AdmissionPredicate() {
    }

    public static MappingOutcome<List<LogicalColumn>> evaluate(PhysicalDatabase database, PhysicalTable table) {
        MappingOutcome<List<LogicalColumn>> tableOutcome = validateTableBaseline(database, table);
        if (tableOutcome != null) {
            return tableOutcome;
        }

        Set<String> columnNames = new HashSet<>();
        for (PhysicalColumn column : table.columns()) {
            if (!columnNames.add(column.columnName())) {
                return MappingOutcome.reject("重复列名不可映射: " + column.columnName());
            }
            try {
                IdentifierValidator.validate(column.columnName());
            }
            catch (IllegalArgumentException exception) {
                return MappingOutcome.reject("列标识符非法: " + column.columnName());
            }
            if (!PhysicalStructureAdmission.hasRequiredColumnBaseline(column)) {
                return MappingOutcome.reject("列级字符集/排序规则覆盖基线: " + column.columnName());
            }
            if (column.characterSet() != null && !REQUIRED_CHARSET.equals(column.characterSet())) {
                return MappingOutcome.reject("列级字符集覆盖基线: " + column.columnName());
            }
            if (column.collation() != null && !REQUIRED_COLLATION.equals(column.collation())) {
                return MappingOutcome.reject("列级排序规则覆盖基线: " + column.columnName());
            }
        }
        if (!PhysicalStructureAdmission.hasRequiredBaseline(table)) {
            return MappingOutcome.reject("物理结构基线不符");
        }

        MappingOutcome<List<LogicalColumn>> primaryOutcome = validatePrimaryKey(table);
        if (primaryOutcome != null) {
            return primaryOutcome;
        }

        Map<String, Boolean> uniqueByColumn = new HashMap<>();
        Map<String, Boolean> indexedByColumn = new HashMap<>();
        Set<String> indexedColumns = new HashSet<>();
        for (PhysicalIndex index : table.secondaryIndexes()) {
            if (!SchemaInspector.isMappableSingleColumnIndex(index)) {
                return MappingOutcome.reject("索引不可映射为单列布尔模型: " + index.indexName());
            }
            String columnName = index.parts().get(0).columnName();
            if (!columnNames.contains(columnName)) {
                return MappingOutcome.reject("索引引用不存在的列: " + index.indexName());
            }
            if (!indexedColumns.add(columnName)) {
                return MappingOutcome.reject("同列重复单列索引: " + columnName);
            }
            if (index.unique()) {
                uniqueByColumn.put(columnName, true);
            }
            else {
                indexedByColumn.put(columnName, true);
            }
        }

        List<LogicalColumn> logicalColumns = new ArrayList<>(table.columns().size());
        for (PhysicalColumn column : table.columns()) {
            MappingOutcome<LogicalColumn> outcome = LogicalModelMapper.toLogical(column,
                    uniqueByColumn.getOrDefault(column.columnName(), false),
                    indexedByColumn.getOrDefault(column.columnName(), false));
            if (!outcome.ok()) {
                return MappingOutcome.reject(outcome.rejectReason());
            }
            logicalColumns.add(outcome.value());
        }
        try {
            IndexAdmission.validateFinalStructure(logicalColumns, table.secondaryIndexes().size());
        }
        catch (BaasBadRequestException exception) {
            return MappingOutcome.reject(exception.getMessage());
        }
        return MappingOutcome.success(List.copyOf(logicalColumns));
    }

    private static MappingOutcome<List<LogicalColumn>> validateTableBaseline(PhysicalDatabase database,
            PhysicalTable table) {
        if (table == null) {
            return MappingOutcome.reject("物理表不存在");
        }
        if (!BASE_TABLE.equals(table.tableType())) {
            return MappingOutcome.reject("VIEW 不可映射");
        }
        if (!REQUIRED_ENGINE.equals(table.engine())) {
            return MappingOutcome.reject("非 InnoDB 引擎破坏批量插入事务契约: " + table.engine());
        }
        if (table.hasTriggers()) {
            return MappingOutcome.reject("存在表级触发器");
        }
        if (table.hasForeignKeys()) {
            return MappingOutcome.reject("存在外键");
        }
        if (table.hasCheckConstraints()) {
            return MappingOutcome.reject("存在 CHECK 约束(含 NOT ENFORCED)");
        }
        if (database == null || !REQUIRED_CHARSET.equals(database.charset())
                || !REQUIRED_COLLATION.equals(database.collation())) {
            return MappingOutcome.reject("库级物理基线不符");
        }
        if (!REQUIRED_COLLATION.equals(table.collation()) || !REQUIRED_ROW_FORMAT.equals(table.rowFormat())) {
            return MappingOutcome.reject("表级物理基线不符(collation/row_format)");
        }
        try {
            IdentifierValidator.validate(table.tableName());
        }
        catch (IllegalArgumentException exception) {
            return MappingOutcome.reject("表标识符非法: " + table.tableName());
        }
        return null;
    }

    private static MappingOutcome<List<LogicalColumn>> validatePrimaryKey(PhysicalTable table) {
        List<PhysicalColumn> primaryColumns = table.columns()
            .stream()
            .filter(PhysicalColumn::isPrimaryKey)
            .toList();
        PhysicalColumn idColumn = table.findColumn("id");
        List<PhysicalIndex> primaryIndexes = table.indexes()
            .stream()
            .filter(index -> PRIMARY_INDEX_NAME.equals(index.indexName()))
            .toList();
        boolean primaryIndexValid = primaryIndexes.size() == 1 && primaryIndexes.get(0).unique()
                && SchemaInspector.isMappableSingleColumnIndex(primaryIndexes.get(0))
                && "id".equals(primaryIndexes.get(0).parts().get(0).columnName());
        boolean idColumnValid = primaryColumns.size() == 1 && idColumn != null && idColumn.isPrimaryKey()
                && "bigint".equalsIgnoreCase(idColumn.dataType()) && !idColumn.isUnsignedOrZerofill()
                && idColumn.isAutoIncrement();
        if (!primaryIndexValid || !idColumnValid) {
            return MappingOutcome.reject("主键不变量破坏(要求唯一主键 id bigint 自增)");
        }
        return null;
    }

}
