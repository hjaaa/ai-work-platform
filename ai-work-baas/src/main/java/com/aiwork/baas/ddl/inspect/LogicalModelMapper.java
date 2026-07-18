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
import com.aiwork.baas.ddl.type.ColumnTypeValidator;
import com.aiwork.baas.ddl.type.DefaultValueRenderer;
import com.aiwork.baas.ddl.type.LogicalColumn;

import java.util.Locale;

/**
 * 物理列 → 逻辑列规范化(spec §13/§9.4):boolean=tinyint(1)、显示宽度忽略、 DEFAULT_GENERATED
 * 归一、UNSIGNED/ZEROFILL/生成列/on update 等一律拒绝映射。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public final class LogicalModelMapper {

    private LogicalModelMapper() {
    }

    public static MappingOutcome<LogicalColumn> toLogical(PhysicalColumn column, boolean unique, boolean indexed) {
        if (column.isGenerated()) {
            return MappingOutcome.reject("生成列不可映射: " + column.columnName());
        }
        if (column.isUnsignedOrZerofill()) {
            return MappingOutcome.reject("UNSIGNED/ZEROFILL 不建模: " + column.columnName());
        }
        String extraOutcome = checkExtra(column);
        if (extraOutcome != null) {
            return MappingOutcome.reject(extraOutcome);
        }

        ColumnType type;
        Integer length = null;
        Integer scale = null;
        String dataType = column.dataType() == null ? "" : column.dataType().toLowerCase(Locale.ROOT);
        switch (dataType) {
            case "bigint" -> type = ColumnType.BIGINT;
            case "int" -> type = ColumnType.INT;
            case "decimal" -> {
                type = ColumnType.DECIMAL;
                length = column.numericPrecision() == null ? null : column.numericPrecision().intValue();
                scale = column.numericScale() == null ? 0 : column.numericScale().intValue();
            }
            case "varchar" -> {
                type = ColumnType.VARCHAR;
                length = column.charMaxLength() == null ? null : column.charMaxLength().intValue();
                if (length == null || length < 1 || length > ColumnTypeValidator.VARCHAR_MAX_LENGTH) {
                    return MappingOutcome.reject("varchar 长度超出白名单: " + column.columnName());
                }
            }
            case "text" -> type = ColumnType.TEXT;
            case "tinyint" -> {
                if (!"tinyint(1)".equalsIgnoreCase(column.columnType())) {
                    return MappingOutcome.reject("tinyint 变体不可映射为 boolean: " + column.columnType());
                }
                type = ColumnType.BOOLEAN;
            }
            case "date" -> type = ColumnType.DATE;
            case "datetime" -> {
                if (column.datetimePrecision() != null && column.datetimePrecision() != 0) {
                    return MappingOutcome.reject("datetime 小数秒精度不可映射: " + column.columnName());
                }
                type = ColumnType.DATETIME;
            }
            case "json" -> type = ColumnType.JSON;
            default -> {
                return MappingOutcome.reject("列类型超出白名单: " + dataType);
            }
        }

        String defaultValue = normalizeDefault(type, column);
        return MappingOutcome
            .success(new LogicalColumn(column.columnName(), type, length, scale, column.nullable(), defaultValue,
                    column.isPrimaryKey(), column.isAutoIncrement(), unique, indexed, emptyToNull(column.comment())));
    }

    /**
     * 列 EXTRA 允许集合(spec §9.4):id 列仅 auto_increment;datetime CURRENT_TIMESTAMP 默认值的
     * DEFAULT_GENERATED 规范化;普通列仅空 EXTRA;其余(on update CURRENT_TIMESTAMP 等)拒绝。
     * @param column 物理列
     * @return 拒绝原因,可映射返回 null
     */
    private static String checkExtra(PhysicalColumn column) {
        String extra = column.extra() == null ? "" : column.extra().toLowerCase(Locale.ROOT);
        if (extra.isEmpty()) {
            return null;
        }
        if ("default_generated".equals(extra) && "datetime".equalsIgnoreCase(column.dataType())
                && DefaultValueRenderer.CURRENT_TIMESTAMP.equalsIgnoreCase(column.columnDefault())) {
            return null;
        }
        if ("auto_increment".equals(extra) && column.isPrimaryKey()) {
            return null;
        }
        return "列 EXTRA 属性不可映射: " + column.extra();
    }

    private static String normalizeDefault(ColumnType type, PhysicalColumn column) {
        String raw = column.columnDefault();
        if (raw == null) {
            return null;
        }
        if (type == ColumnType.BOOLEAN) {
            return "0".equals(raw) ? "false" : "1".equals(raw) ? "true" : raw;
        }
        if (type == ColumnType.DATETIME && DefaultValueRenderer.CURRENT_TIMESTAMP.equalsIgnoreCase(raw)) {
            return DefaultValueRenderer.CURRENT_TIMESTAMP;
        }
        return raw;
    }

    private static String emptyToNull(String text) {
        return text == null || text.isEmpty() ? null : text;
    }

}
