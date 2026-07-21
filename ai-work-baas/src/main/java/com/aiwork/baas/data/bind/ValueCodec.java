package com.aiwork.baas.data.bind;

import com.aiwork.baas.data.error.DataApiException;
import com.aiwork.baas.data.query.FilterOperator;
import com.aiwork.baas.ddl.type.ColumnType;
import com.aiwork.baas.entity.BaasColumn;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/**
 * 线协议编解码(spec §7.1 矩阵):解析/绑定严格按逻辑类型,输出与输入对称。
 *
 * @author ai-work
 * @date 2026/07/21
 */
@Component
public class ValueCodec {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd")
        .withResolverStyle(ResolverStyle.STRICT);

    private static final DateTimeFormatter DATETIME_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
        .withResolverStyle(ResolverStyle.STRICT);

    /**
     * 将 URL 过滤值解析为 JDBC 绑定值。
     *
     * @param column 列元数据
     * @param operator 过滤操作符
     * @param raw 原始过滤值
     * @return JDBC 绑定值
     */
    public Object parseFilterValue(BaasColumn column, FilterOperator operator, String raw) {
        ColumnType type = ColumnType.fromCode(column.getDataType());
        if (type == ColumnType.JSON) {
            throw DataApiException
                .badRequest("json 列仅支持 is.null / is.not_null 过滤: " + column.getColumnName());
        }
        if (operator == FilterOperator.LIKE && type != ColumnType.VARCHAR && type != ColumnType.TEXT) {
            throw DataApiException.badRequest("like 仅支持字符串列: " + column.getColumnName());
        }
        return switch (type) {
            case BIGINT -> parseLong(column, raw);
            case INT -> parseInt(column, raw);
            case DECIMAL -> checkDecimal(column, parseBigDecimal(column, raw));
            case BOOLEAN -> parseBooleanLiteral(column, raw);
            case VARCHAR -> checkVarcharLength(column, raw);
            case TEXT -> raw;
            case DATE -> parseDate(column, raw);
            case DATETIME -> parseDatetime(column, raw);
            case JSON -> throw new IllegalStateException("unreachable");
        };
    }

    /**
     * 将 body JSON 节点解析为 JDBC 绑定值。
     *
     * @param column 列元数据
     * @param node 非 null 且非 NullNode 的 JSON 节点
     * @return JDBC 绑定值
     */
    public Object parseBodyValue(BaasColumn column, JsonNode node) {
        ColumnType type = ColumnType.fromCode(column.getDataType());
        return switch (type) {
            case BIGINT -> requireIntegral(column, node);
            case INT -> {
                long value = requireIntegral(column, node);
                if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                    throw valueError(column, "超出 int 值域");
                }
                yield value;
            }
            case DECIMAL -> {
                BigDecimal decimal;
                if (node.isNumber()) {
                    decimal = node.decimalValue();
                } else if (node.isTextual()) {
                    decimal = parseBigDecimal(column, node.textValue());
                } else {
                    throw valueError(column, "decimal 仅接受 number 或数字字符串");
                }
                yield checkDecimal(column, decimal);
            }
            case BOOLEAN -> {
                if (!node.isBoolean()) {
                    throw valueError(column, "boolean 仅接受 JSON true/false");
                }
                yield node.booleanValue();
            }
            case VARCHAR -> {
                if (!node.isTextual()) {
                    throw valueError(column, "varchar 仅接受字符串");
                }
                yield checkVarcharLength(column, node.textValue());
            }
            case TEXT -> {
                if (!node.isTextual()) {
                    throw valueError(column, "text 仅接受字符串");
                }
                yield node.textValue();
            }
            case DATE -> {
                if (!node.isTextual()) {
                    throw valueError(column, "date 仅接受 yyyy-MM-dd 字符串");
                }
                yield parseDate(column, node.textValue());
            }
            case DATETIME -> {
                if (!node.isTextual()) {
                    throw valueError(column, "datetime 仅接受 yyyy-MM-dd HH:mm:ss 字符串");
                }
                yield parseDatetime(column, node.textValue());
            }
            case JSON -> node.toString();
        };
    }

    /**
     * 将 JDBC 列值按线协议类型写入 JSON 生成器。
     *
     * @param generator JSON 生成器
     * @param column 列元数据
     * @param resultSet JDBC 结果集
     * @param columnIndex 列索引
     * @throws IOException JSON 写入失败
     * @throws SQLException JDBC 读取失败
     */
    public void writeColumn(JsonGenerator generator, BaasColumn column, ResultSet resultSet, int columnIndex)
            throws IOException, SQLException {
        ColumnType type = ColumnType.fromCode(column.getDataType());
        switch (type) {
            case BIGINT, INT -> {
                long value = resultSet.getLong(columnIndex);
                writeOrNull(generator, resultSet, () -> generator.writeNumber(value));
            }
            case DECIMAL -> {
                BigDecimal value = resultSet.getBigDecimal(columnIndex);
                if (value == null) {
                    generator.writeNull();
                } else {
                    generator.writeNumber(value);
                }
            }
            case BOOLEAN -> {
                boolean value = resultSet.getBoolean(columnIndex);
                writeOrNull(generator, resultSet, () -> generator.writeBoolean(value));
            }
            case VARCHAR, TEXT -> {
                String value = resultSet.getString(columnIndex);
                if (value == null) {
                    generator.writeNull();
                } else {
                    generator.writeString(value);
                }
            }
            case DATE -> {
                LocalDate value = resultSet.getObject(columnIndex, LocalDate.class);
                if (value == null) {
                    generator.writeNull();
                } else {
                    generator.writeString(DATE_FORMAT.format(value));
                }
            }
            case DATETIME -> {
                LocalDateTime value = resultSet.getObject(columnIndex, LocalDateTime.class);
                if (value == null) {
                    generator.writeNull();
                } else {
                    generator.writeString(DATETIME_FORMAT.format(value));
                }
            }
            case JSON -> {
                String value = resultSet.getString(columnIndex);
                if (value == null) {
                    generator.writeNull();
                } else {
                    generator.writeRawValue(value);
                }
            }
            default -> throw new IllegalStateException("unreachable");
        }
    }

    @FunctionalInterface
    private interface JsonWrite {

        /**
         * 写入 JSON 值。
         *
         * @throws IOException JSON 写入失败
         */
        void write() throws IOException;

    }

    private static void writeOrNull(JsonGenerator generator, ResultSet resultSet, JsonWrite write)
            throws IOException, SQLException {
        if (resultSet.wasNull()) {
            generator.writeNull();
        } else {
            write.write();
        }
    }

    private static long requireIntegral(BaasColumn column, JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw valueError(column, "仅接受整数 number");
        }
        return node.longValue();
    }

    private static long parseLong(BaasColumn column, String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException exception) {
            throw valueError(column, "非法整数");
        }
    }

    private static int parseInt(BaasColumn column, String raw) {
        long value = parseLong(column, raw);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw valueError(column, "超出 int 值域");
        }
        return (int)value;
    }

    private static BigDecimal parseBigDecimal(BaasColumn column, String raw) {
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException exception) {
            throw valueError(column, "非法数值");
        }
    }

    private static BigDecimal checkDecimal(BaasColumn column, BigDecimal value) {
        int precision = column.getLength();
        int scale = column.getScale() == null ? 0 : column.getScale();
        if (value.scale() > scale || value.precision() - value.scale() > precision - scale) {
            throw valueError(column, "超出 decimal(" + precision + "," + scale + ") 值域");
        }
        return value;
    }

    private static Boolean parseBooleanLiteral(BaasColumn column, String raw) {
        if ("true".equals(raw)) {
            return Boolean.TRUE;
        }
        if ("false".equals(raw)) {
            return Boolean.FALSE;
        }
        throw valueError(column, "boolean 仅接受 true/false");
    }

    private static String checkVarcharLength(BaasColumn column, String value) {
        if (column.getLength() != null && value.length() > column.getLength()) {
            throw valueError(column, "超出 varchar(" + column.getLength() + ") 长度");
        }
        return value;
    }

    private static LocalDate parseDate(BaasColumn column, String raw) {
        try {
            return LocalDate.parse(raw, DATE_FORMAT);
        } catch (DateTimeParseException exception) {
            throw valueError(column, "date 须为 yyyy-MM-dd");
        }
    }

    private static LocalDateTime parseDatetime(BaasColumn column, String raw) {
        try {
            return LocalDateTime.parse(raw, DATETIME_FORMAT);
        } catch (DateTimeParseException exception) {
            throw valueError(column, "datetime 须为 yyyy-MM-dd HH:mm:ss");
        }
    }

    private static DataApiException valueError(BaasColumn column, String reason) {
        return DataApiException.badRequest("列 " + column.getColumnName() + " 值非法: " + reason);
    }

}
