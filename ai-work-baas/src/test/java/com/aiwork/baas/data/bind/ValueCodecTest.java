package com.aiwork.baas.data.bind;

import com.aiwork.baas.data.error.DataApiException;
import com.aiwork.baas.data.query.FilterOperator;
import com.aiwork.baas.entity.BaasColumn;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 线协议矩阵单测(spec §7.1:逻辑类型 × token × 绑定 × 输出)。
 *
 * @author ai-work
 * @date 2026/07/21
 */
class ValueCodecTest {

    private final ValueCodec codec = new ValueCodec();

    private final ObjectMapper mapper = JsonMapper.builder()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .build();

    private static BaasColumn column(String name, String dataType, Integer length, Integer scale) {
        BaasColumn column = new BaasColumn();
        column.setColumnName(name);
        column.setDataType(dataType);
        column.setLength(length);
        column.setScale(scale);
        column.setNullable(true);
        return column;
    }

    // ---- 过滤值解析 ----

    @Test
    void filterBigintParsesToLong() {
        assertThat(codec.parseFilterValue(column("c", "bigint", null, null), FilterOperator.EQ, "9007199254740993"))
            .isEqualTo(9007199254740993L);
    }

    @Test
    void filterIntRejectsOutOfRange() {
        assertThatThrownBy(
                () -> codec.parseFilterValue(column("c", "int", null, null), FilterOperator.EQ, "2147483648"))
            .isInstanceOf(DataApiException.class);
    }

    @Test
    void filterDecimalRespectsPrecisionAndScale() {
        BaasColumn decimal = column("c", "decimal", 5, 2);
        assertThat(codec.parseFilterValue(decimal, FilterOperator.GT, "123.45")).isEqualTo(new BigDecimal("123.45"));
        assertThatThrownBy(() -> codec.parseFilterValue(decimal, FilterOperator.GT, "1234.5"))
            .isInstanceOf(DataApiException.class);
        assertThatThrownBy(() -> codec.parseFilterValue(decimal, FilterOperator.GT, "1.234"))
            .isInstanceOf(DataApiException.class);
    }

    @Test
    void filterBooleanOnlyTrueFalse() {
        BaasColumn bool = column("c", "boolean", null, null);
        assertThat(codec.parseFilterValue(bool, FilterOperator.EQ, "true")).isEqualTo(Boolean.TRUE);
        assertThatThrownBy(() -> codec.parseFilterValue(bool, FilterOperator.EQ, "1"))
            .isInstanceOf(DataApiException.class);
    }

    @Test
    void filterDateAndDatetimeStrictFormat() {
        assertThat(codec.parseFilterValue(column("c", "date", null, null), FilterOperator.EQ, "2026-07-21"))
            .isEqualTo(LocalDate.of(2026, 7, 21));
        assertThat(codec.parseFilterValue(column("c", "datetime", null, null), FilterOperator.LT,
                "2026-07-21 10:30:00"))
            .isEqualTo(LocalDateTime.of(2026, 7, 21, 10, 30, 0));
        assertThatThrownBy(
                () -> codec.parseFilterValue(column("c", "date", null, null), FilterOperator.EQ, "2026-02-30"))
            .isInstanceOf(DataApiException.class);
        assertThatThrownBy(() -> codec.parseFilterValue(column("c", "datetime", null, null), FilterOperator.EQ,
                "2026-07-21T10:30:00"))
            .isInstanceOf(DataApiException.class);
    }

    @Test
    void filterLikeOnlyOnStringColumns() {
        assertThat(codec.parseFilterValue(column("c", "varchar", 64, null), FilterOperator.LIKE, "a%"))
            .isEqualTo("a%");
        assertThatThrownBy(() -> codec.parseFilterValue(column("c", "bigint", null, null), FilterOperator.LIKE, "1%"))
            .isInstanceOf(DataApiException.class);
    }

    @Test
    void filterJsonColumnRejected() {
        assertThatThrownBy(() -> codec.parseFilterValue(column("c", "json", null, null), FilterOperator.EQ, "x"))
            .isInstanceOf(DataApiException.class);
    }

    @Test
    void filterVarcharOverLengthRejected() {
        assertThatThrownBy(() -> codec.parseFilterValue(column("c", "varchar", 3, null), FilterOperator.EQ, "abcd"))
            .isInstanceOf(DataApiException.class);
    }

    // ---- body 值解析 ----

    @Test
    void bodyIntegerRejectsStringAndFraction() throws Exception {
        BaasColumn bigint = column("c", "bigint", null, null);
        assertThat(codec.parseBodyValue(bigint, mapper.readTree("42"))).isEqualTo(42L);
        assertThatThrownBy(() -> codec.parseBodyValue(bigint, mapper.readTree("\"42\"")))
            .isInstanceOf(DataApiException.class);
        assertThatThrownBy(() -> codec.parseBodyValue(bigint, mapper.readTree("4.2")))
            .isInstanceOf(DataApiException.class);
    }

    @Test
    void bodyDecimalAcceptsNumberAndNumericString() throws Exception {
        BaasColumn decimal = column("c", "decimal", 10, 2);
        assertThat(codec.parseBodyValue(decimal, mapper.readTree("19.99"))).isEqualTo(new BigDecimal("19.99"));
        assertThat(codec.parseBodyValue(decimal, mapper.readTree("\"19.99\""))).isEqualTo(new BigDecimal("19.99"));
        assertThatThrownBy(() -> codec.parseBodyValue(decimal, mapper.readTree("\"abc\"")))
            .isInstanceOf(DataApiException.class);
    }

    @Test
    void bodyBooleanRejectsNumericAndString() throws Exception {
        BaasColumn bool = column("c", "boolean", null, null);
        assertThat(codec.parseBodyValue(bool, mapper.readTree("true"))).isEqualTo(Boolean.TRUE);
        assertThatThrownBy(() -> codec.parseBodyValue(bool, mapper.readTree("1")))
            .isInstanceOf(DataApiException.class);
        assertThatThrownBy(() -> codec.parseBodyValue(bool, mapper.readTree("\"true\"")))
            .isInstanceOf(DataApiException.class);
    }

    @Test
    void bodyJsonColumnAcceptsAnyJsonValue() throws Exception {
        BaasColumn json = column("c", "json", null, null);
        assertThat(codec.parseBodyValue(json, mapper.readTree("{\"a\":[1,2]}"))).isEqualTo("{\"a\":[1,2]}");
        assertThat(codec.parseBodyValue(json, mapper.readTree("[1,2]"))).isEqualTo("[1,2]");
        assertThat(codec.parseBodyValue(json, mapper.readTree("\"scalar\""))).isEqualTo("\"scalar\"");
    }

    @Test
    void bodyVarcharOverLengthRejected() throws Exception {
        assertThatThrownBy(() -> codec.parseBodyValue(column("c", "varchar", 3, null), mapper.readTree("\"abcd\"")))
            .isInstanceOf(DataApiException.class);
    }

    // ---- 输出 ----

    @Test
    void writesJsonColumnAsRealJson() throws Exception {
        StringWriter out = new StringWriter();
        try (JsonGenerator generator = mapper.getFactory().createGenerator(out)) {
            generator.writeStartObject();
            generator.writeFieldName("payload");
            codec.writeColumn(generator, column("payload", "json", null, null),
                    SingleValueResultSet.of("{\"a\": 1}"), 1);
            generator.writeEndObject();
        }
        assertThat(out.toString()).isEqualTo("{\"payload\":{\"a\": 1}}");
    }

    @Test
    void writesBooleanAsTrueFalse() throws Exception {
        StringWriter out = new StringWriter();
        try (JsonGenerator generator = mapper.getFactory().createGenerator(out)) {
            generator.writeStartObject();
            generator.writeFieldName("ok");
            codec.writeColumn(generator, column("ok", "boolean", null, null), SingleValueResultSet.of(Boolean.TRUE),
                    1);
            generator.writeEndObject();
        }
        assertThat(out.toString()).isEqualTo("{\"ok\":true}");
    }

    @Test
    void writesDatetimeCanonicalFormat() throws Exception {
        StringWriter out = new StringWriter();
        try (JsonGenerator generator = mapper.getFactory().createGenerator(out)) {
            generator.writeStartObject();
            generator.writeFieldName("ts");
            codec.writeColumn(generator, column("ts", "datetime", null, null),
                    SingleValueResultSet.of(LocalDateTime.of(2026, 7, 21, 9, 5, 3)), 1);
            generator.writeEndObject();
        }
        assertThat(out.toString()).isEqualTo("{\"ts\":\"2026-07-21 09:05:03\"}");
    }

    @Test
    void writesSqlNullAsJsonNull() throws Exception {
        StringWriter out = new StringWriter();
        try (JsonGenerator generator = mapper.getFactory().createGenerator(out)) {
            generator.writeStartObject();
            generator.writeFieldName("v");
            codec.writeColumn(generator, column("v", "bigint", null, null), SingleValueResultSet.of(null), 1);
            generator.writeEndObject();
        }
        assertThat(out.toString()).isEqualTo("{\"v\":null}");
    }

}
