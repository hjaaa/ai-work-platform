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

/**
 * 规范化后的逻辑列模型(spec §13):管理 API 校验、DDL 渲染、对账与探测续跑共用。
 * defaultValue 为规范化字符串(数值原文/true/false/日期时间原文/varchar 原文/CURRENT_TIMESTAMP),无默认值为 null。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public record LogicalColumn(String columnName, ColumnType type, Integer length, Integer scale, boolean nullable,
        String defaultValue, boolean pk, boolean autoIncrement, boolean unique, boolean indexed, String comment) {

    public LogicalColumn {
        scale = ColumnTypeValidator.normalizeScale(type, scale);
        indexed = unique ? false : indexed;
        comment = comment != null && comment.isEmpty() ? null : comment;
    }

    /** 类型形状(类型 + 参数)是否一致,用于对账比较与探测续跑。 */
    public boolean sameShape(LogicalColumn other) {
        return type == other.type && java.util.Objects.equals(length, other.length)
                && java.util.Objects.equals(scale, other.scale);
    }

}
