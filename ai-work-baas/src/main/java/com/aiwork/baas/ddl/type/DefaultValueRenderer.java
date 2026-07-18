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

import com.aiwork.baas.exception.BaasBadRequestException;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;

/**
 * 默认值类型化模型(spec §7.3):JSON 标量按列类型严格解析后重新渲染为规范字面量,
 * 客户端字符串一律不拼接进 DDL 原文。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public final class DefaultValueRenderer {

    public static final String CURRENT_TIMESTAMP = "CURRENT_TIMESTAMP";

    private static final BigInteger INT_MIN = BigInteger.valueOf(Integer.MIN_VALUE);

    private static final BigInteger INT_MAX = BigInteger.valueOf(Integer.MAX_VALUE);

    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);

    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd")
        .withResolverStyle(ResolverStyle.STRICT);

    private static final DateTimeFormatter DATETIME_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
        .withResolverStyle(ResolverStyle.STRICT);

    /**
     * @param ddlLiteral 拼入 DDL 的规范字面量(已转义/包裹引号,或 TRUE/FALSE/CURRENT_TIMESTAMP/数值)
     * @param canonical 元数据落库与对账比较用的规范化字符串
     */
    public record Rendered(String ddlLiteral, String canonical) {
    }

    private DefaultValueRenderer() {
    }

    public static Rendered render(ColumnType type, Integer length, Integer scale, JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!type.supportsDefault()) {
            throw new BaasBadRequestException(type.code() + " 列不支持默认值");
        }
        return switch (type) {
            case INT -> integral(value, INT_MIN, INT_MAX);
            case BIGINT -> integral(value, LONG_MIN, LONG_MAX);
            case DECIMAL -> {
                ColumnTypeValidator.validateTypeParams(type.code(), length, scale);
                yield decimal(value, length, ColumnTypeValidator.normalizeScale(type, scale));
            }
            case BOOLEAN -> bool(value);
            case DATE -> date(value);
            case DATETIME -> datetime(value);
            case VARCHAR -> varchar(value, length);
            default -> throw new BaasBadRequestException(type.code() + " 列不支持默认值");
        };
    }

    public static String escapeStringLiteral(String raw) {
        return raw.replace("\\", "\\\\").replace("'", "''");
    }

    private static Rendered integral(JsonNode value, BigInteger min, BigInteger max) {
        if (!value.isIntegralNumber()) {
            throw new BaasBadRequestException("整数列默认值必须为 JSON 整数");
        }
        BigInteger number = value.bigIntegerValue();
        if (number.compareTo(min) < 0 || number.compareTo(max) > 0) {
            throw new BaasBadRequestException("默认值超出目标列值域");
        }
        String text = number.toString();
        return new Rendered(text, text);
    }

    private static Rendered decimal(JsonNode value, int precision, int scale) {
        if (!value.isNumber()) {
            throw new BaasBadRequestException("decimal 列默认值必须为 JSON 数值");
        }
        BigDecimal number = value.decimalValue();
        if (number.scale() > scale) {
            throw new BaasBadRequestException("默认值小数位超出列定义 scale");
        }
        BigDecimal normalized = number.setScale(scale);
        int integerDigits = Math.max(0, normalized.precision() - normalized.scale());
        if (integerDigits > precision - scale) {
            throw new BaasBadRequestException("默认值整数位超出列定义精度");
        }
        String text = normalized.toPlainString();
        return new Rendered(text, text);
    }

    private static Rendered bool(JsonNode value) {
        if (!value.isBoolean()) {
            throw new BaasBadRequestException("boolean 列默认值必须为 JSON true/false");
        }
        return value.booleanValue() ? new Rendered("TRUE", "true") : new Rendered("FALSE", "false");
    }

    private static Rendered date(JsonNode value) {
        String text = requireText(value);
        try {
            DATE_FORMAT.parse(text);
        }
        catch (Exception exception) {
            throw new BaasBadRequestException("date 默认值格式须为 yyyy-MM-dd");
        }
        return new Rendered("'" + text + "'", text);
    }

    private static Rendered datetime(JsonNode value) {
        String text = requireText(value);
        if (CURRENT_TIMESTAMP.equalsIgnoreCase(text)) {
            return new Rendered(CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
        }
        try {
            DATETIME_FORMAT.parse(text);
        }
        catch (Exception exception) {
            throw new BaasBadRequestException("datetime 默认值格式须为 yyyy-MM-dd HH:mm:ss 或 CURRENT_TIMESTAMP");
        }
        return new Rendered("'" + text + "'", text);
    }

    private static Rendered varchar(JsonNode value, Integer length) {
        String text = requireText(value);
        if ("CURRENT_TIMESTAMP()".equalsIgnoreCase(text)) {
            throw new BaasBadRequestException("varchar 列默认值不支持函数表达式");
        }
        if (length != null && text.length() > length) {
            throw new BaasBadRequestException("varchar 默认值长度超出列定义");
        }
        return new Rendered("'" + escapeStringLiteral(text) + "'", text);
    }

    private static String requireText(JsonNode value) {
        if (!value.isTextual()) {
            throw new BaasBadRequestException("默认值必须为 JSON 标量且与列类型匹配");
        }
        return value.textValue();
    }

}
