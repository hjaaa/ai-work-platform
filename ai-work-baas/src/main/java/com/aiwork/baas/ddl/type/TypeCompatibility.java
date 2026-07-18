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

package com.aiwork.baas.ddl.type;

import java.util.Objects;

/**
 * 类型兼容矩阵(spec §13):只维护无损集合,集合外一律要求 allowLossy。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public final class TypeCompatibility {

    private TypeCompatibility() {
    }

    public static boolean isLossless(LogicalColumn from, LogicalColumn to) {
        if (from.nullable() && !to.nullable()) {
            return false;
        }
        if (from.type() == to.type()) {
            return switch (from.type()) {
                case VARCHAR -> to.length() != null && from.length() != null && to.length() >= from.length();
                case DECIMAL -> scaleOf(to) >= scaleOf(from)
                        && to.length() - scaleOf(to) >= from.length() - scaleOf(from);
                default -> Objects.equals(from.length(), to.length()) && Objects.equals(from.scale(), to.scale());
            };
        }
        if (from.type() == ColumnType.INT && to.type() == ColumnType.BIGINT) {
            return true;
        }
        if (from.type() == ColumnType.VARCHAR && to.type() == ColumnType.TEXT) {
            return true;
        }
        return from.type() == ColumnType.DATE && to.type() == ColumnType.DATETIME;
    }

    private static int scaleOf(LogicalColumn column) {
        return column.scale() == null ? 0 : column.scale();
    }

}
