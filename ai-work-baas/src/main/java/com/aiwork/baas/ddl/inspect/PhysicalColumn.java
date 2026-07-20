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

package com.aiwork.baas.ddl.inspect;

/**
 * information_schema.COLUMNS 单行原始视图。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public record PhysicalColumn(String columnName, String dataType, String columnType, Long charMaxLength,
        Long numericPrecision, Long numericScale, Long datetimePrecision, boolean nullable, String columnDefault,
        String extra, String characterSet, String collation, String columnKey, String comment,
        String generationExpression) {

    public boolean isPrimaryKey() {
        return "PRI".equals(columnKey);
    }

    public boolean isAutoIncrement() {
        return extra != null && extra.toLowerCase().contains("auto_increment");
    }

    public boolean isGenerated() {
        return generationExpression != null && !generationExpression.isEmpty();
    }

    public boolean isUnsignedOrZerofill() {
        String lowered = columnType == null ? "" : columnType.toLowerCase();
        return lowered.contains("unsigned") || lowered.contains("zerofill");
    }

}
