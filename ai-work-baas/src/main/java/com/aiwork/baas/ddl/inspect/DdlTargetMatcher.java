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

import com.aiwork.baas.ddl.type.LogicalColumn;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 完整物理目标匹配器；NEW_OPERATION 执行前不得据此接管外部表，受控 DDL 后可用于终态确认。 */
public final class DdlTargetMatcher {

    private DdlTargetMatcher() {
    }

    public static boolean matches(PhysicalTable actual, String expectedName, String expectedComment,
            List<LogicalColumn> expectedColumns) {
        if (actual == null || !Objects.equals(actual.tableName(), expectedName)
                || !Objects.equals(emptyToNull(actual.tableComment()), emptyToNull(expectedComment))
                || !PhysicalStructureAdmission.hasRequiredBaseline(actual)
                || actual.columns().size() != expectedColumns.size()) {
            return false;
        }
        List<PhysicalIndex> primary = actual.indexes()
            .stream()
            .filter(index -> "PRIMARY".equals(index.indexName()))
            .toList();
        if (primary.size() != 1 || !primary.get(0).unique()
                || !SchemaInspector.isMappableSingleColumnIndex(primary.get(0))
                || !"id".equals(primary.get(0).parts().get(0).columnName())) {
            return false;
        }

        Map<String, PhysicalIndex> secondaryByColumn = new HashMap<>();
        for (PhysicalIndex index : actual.secondaryIndexes()) {
            if (!SchemaInspector.isMappableSingleColumnIndex(index)) {
                return false;
            }
            if (secondaryByColumn.put(index.parts().get(0).columnName(), index) != null) {
                return false;
            }
        }
        long expectedSecondaryCount = expectedColumns.stream()
            .filter(column -> column.unique() || column.indexed())
            .count();
        if (secondaryByColumn.size() != expectedSecondaryCount) {
            return false;
        }

        for (LogicalColumn expected : expectedColumns) {
            PhysicalColumn physicalColumn = actual.findColumn(expected.columnName());
            if (physicalColumn == null) {
                return false;
            }
            PhysicalIndex index = secondaryByColumn.get(expected.columnName());
            boolean unique = index != null && index.unique();
            boolean indexed = index != null && !index.unique();
            MappingOutcome<LogicalColumn> mapped = LogicalModelMapper.toLogical(physicalColumn, unique, indexed);
            if (!mapped.ok() || !expected.equals(mapped.value())) {
                return false;
            }
        }
        return true;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

}
