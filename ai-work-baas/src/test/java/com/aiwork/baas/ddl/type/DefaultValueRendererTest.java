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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultValueRendererTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void nullValueMeansNoDefault() {
        assertThat(DefaultValueRenderer.render(ColumnType.INT, null, null, null)).isNull();
        assertThat(DefaultValueRenderer.render(ColumnType.INT, null, null, NullNode.getInstance())).isNull();
    }

    @Test
    void intAndBigintRenderPlainNumbers() {
        var rendered = DefaultValueRenderer.render(ColumnType.INT, null, null, MAPPER.getNodeFactory().numberNode(42));
        assertThat(rendered.ddlLiteral()).isEqualTo("42");
        assertThat(rendered.canonical()).isEqualTo("42");
        assertThat(DefaultValueRenderer
            .render(ColumnType.BIGINT, null, null, MAPPER.getNodeFactory().numberNode(Long.MAX_VALUE))
            .ddlLiteral()).isEqualTo("9223372036854775807");
    }

    @Test
    void intOutOfRangeRejected() {
        assertThatThrownBy(() -> DefaultValueRenderer.render(ColumnType.INT, null, null,
                MAPPER.getNodeFactory().numberNode(2147483648L)))
            .isInstanceOf(BaasBadRequestException.class);
        assertThatThrownBy(() -> DefaultValueRenderer.render(ColumnType.BIGINT, null, null,
                MAPPER.getNodeFactory().numberNode(new java.math.BigInteger("9223372036854775808"))))
            .isInstanceOf(BaasBadRequestException.class);
    }

    @Test
    void decimalMustFitPrecisionAndScale() {
        var ok = DefaultValueRenderer.render(ColumnType.DECIMAL, 5, 2,
                MAPPER.getNodeFactory().numberNode(new java.math.BigDecimal("123.45")));
        assertThat(ok.ddlLiteral()).isEqualTo("123.45");
        assertThatThrownBy(() -> DefaultValueRenderer.render(ColumnType.DECIMAL, 5, 2,
                MAPPER.getNodeFactory().numberNode(new java.math.BigDecimal("1234.5"))))
            .isInstanceOf(BaasBadRequestException.class);
        assertThatThrownBy(() -> DefaultValueRenderer.render(ColumnType.DECIMAL, 5, 2,
                MAPPER.getNodeFactory().numberNode(new java.math.BigDecimal("1.234"))))
            .isInstanceOf(BaasBadRequestException.class);
    }

    @Test
    void decimalCanonicalUsesDeclaredScaleAndAllowsZeroWithoutIntegerDigits() {
        var zero = DefaultValueRenderer.render(ColumnType.DECIMAL, 2, 2,
                MAPPER.getNodeFactory().numberNode(new java.math.BigDecimal("0")));
        var padded = DefaultValueRenderer.render(ColumnType.DECIMAL, 5, 2,
                MAPPER.getNodeFactory().numberNode(new java.math.BigDecimal("1.2")));

        assertThat(zero.ddlLiteral()).isEqualTo("0.00");
        assertThat(zero.canonical()).isEqualTo("0.00");
        assertThat(padded.ddlLiteral()).isEqualTo("1.20");
        assertThat(padded.canonical()).isEqualTo("1.20");
    }

    @Test
    void decimalRejectsMissingOrInvalidTypeParams() {
        assertThatThrownBy(() -> DefaultValueRenderer.render(ColumnType.DECIMAL, null, 0,
                MAPPER.getNodeFactory().numberNode(1)))
            .isInstanceOf(BaasBadRequestException.class);
        assertThatThrownBy(() -> DefaultValueRenderer.render(ColumnType.DECIMAL, 66, 0,
                MAPPER.getNodeFactory().numberNode(1)))
            .isInstanceOf(BaasBadRequestException.class);
        assertThatThrownBy(() -> DefaultValueRenderer.render(ColumnType.DECIMAL, 1, 2,
                MAPPER.getNodeFactory().numberNode(new java.math.BigDecimal("0.01"))))
            .isInstanceOf(BaasBadRequestException.class);
    }

    @Test
    void booleanRendersTrueFalseKeywords() {
        var rendered = DefaultValueRenderer.render(ColumnType.BOOLEAN, null, null,
                MAPPER.getNodeFactory().booleanNode(true));
        assertThat(rendered.ddlLiteral()).isEqualTo("TRUE");
        assertThat(rendered.canonical()).isEqualTo("true");
        assertThatThrownBy(() -> DefaultValueRenderer.render(ColumnType.BOOLEAN, null, null,
                MAPPER.getNodeFactory().numberNode(1)))
            .isInstanceOf(BaasBadRequestException.class);
    }

    @Test
    void dateAndDatetimeAreStrictlyParsed() {
        assertThat(DefaultValueRenderer
            .render(ColumnType.DATE, null, null, MAPPER.getNodeFactory().textNode("2026-07-18"))
            .ddlLiteral()).isEqualTo("'2026-07-18'");
        assertThat(DefaultValueRenderer
            .render(ColumnType.DATETIME, null, null, MAPPER.getNodeFactory().textNode("2026-07-18 10:30:00"))
            .ddlLiteral()).isEqualTo("'2026-07-18 10:30:00'");
        assertThatThrownBy(() -> DefaultValueRenderer.render(ColumnType.DATE, null, null,
                MAPPER.getNodeFactory().textNode("2026-02-30")))
            .isInstanceOf(BaasBadRequestException.class);
        assertThatThrownBy(() -> DefaultValueRenderer.render(ColumnType.DATETIME, null, null,
                MAPPER.getNodeFactory().textNode("2026-07-18T10:30:00")))
            .isInstanceOf(BaasBadRequestException.class);
    }

    @Test
    void currentTimestampOnlyForDatetimeCaseInsensitive() {
        var rendered = DefaultValueRenderer.render(ColumnType.DATETIME, null, null,
                MAPPER.getNodeFactory().textNode("current_timestamp"));
        assertThat(rendered.ddlLiteral()).isEqualTo("CURRENT_TIMESTAMP");
        assertThat(rendered.canonical()).isEqualTo("CURRENT_TIMESTAMP");
        assertThatThrownBy(() -> DefaultValueRenderer.render(ColumnType.DATE, null, null,
                MAPPER.getNodeFactory().textNode("CURRENT_TIMESTAMP")))
            .isInstanceOf(BaasBadRequestException.class);
        assertThatThrownBy(() -> DefaultValueRenderer.render(ColumnType.VARCHAR, 64, null,
                MAPPER.getNodeFactory().textNode("CURRENT_TIMESTAMP()")))
            .isInstanceOf(BaasBadRequestException.class)
            .satisfies(thrown -> assertThat(thrown.getMessage()).doesNotContain("("));
    }

    @Test
    void varcharEscapesQuotesAndBackslashes() {
        var rendered = DefaultValueRenderer.render(ColumnType.VARCHAR, 64, null,
                MAPPER.getNodeFactory().textNode("a'b\\c"));
        assertThat(rendered.ddlLiteral()).isEqualTo("'a''b\\\\c'");
        assertThat(rendered.canonical()).isEqualTo("a'b\\c");
    }

    @Test
    void varcharInjectionPayloadNeverConcatenatedRaw() {
        String payload = "x', comment='y'); DROP TABLE t; --";
        var rendered = DefaultValueRenderer.render(ColumnType.VARCHAR, 128, null,
                MAPPER.getNodeFactory().textNode(payload));
        assertThat(rendered.ddlLiteral()).startsWith("'").endsWith("'");
        assertThat(rendered.ddlLiteral()).doesNotContain("x', comment");
    }

    @Test
    void varcharDefaultLongerThanColumnRejected() {
        assertThatThrownBy(() -> DefaultValueRenderer.render(ColumnType.VARCHAR, 3, null,
                MAPPER.getNodeFactory().textNode("abcd")))
            .isInstanceOf(BaasBadRequestException.class);
    }

    @Test
    void textAndJsonRejectDefaults() {
        assertThatThrownBy(() -> DefaultValueRenderer.render(ColumnType.TEXT, null, null,
                MAPPER.getNodeFactory().textNode("x")))
            .isInstanceOf(BaasBadRequestException.class);
        assertThatThrownBy(() -> DefaultValueRenderer.render(ColumnType.JSON, null, null,
                MAPPER.getNodeFactory().textNode("{}")))
            .isInstanceOf(BaasBadRequestException.class);
    }

    @Test
    void nonScalarRejected() {
        assertThatThrownBy(() -> DefaultValueRenderer.render(ColumnType.VARCHAR, 64, null,
                MAPPER.createObjectNode()))
            .isInstanceOf(BaasBadRequestException.class);
        assertThatThrownBy(() -> DefaultValueRenderer.render(ColumnType.INT, null, null,
                MAPPER.getNodeFactory().textNode("42")))
            .isInstanceOf(BaasBadRequestException.class);
    }

    @Test
    void physicalDefaultsReuseTypedCanonicalizationRules() {
        assertThat(DefaultValueRenderer.normalizePhysical(ColumnType.BOOLEAN, null, null, "0")).isEqualTo("false");
        assertThat(DefaultValueRenderer.normalizePhysical(ColumnType.BOOLEAN, null, null, "1")).isEqualTo("true");
        assertThat(DefaultValueRenderer.normalizePhysical(ColumnType.DECIMAL, 5, 2, "1.2")).isEqualTo("1.20");
        assertThat(DefaultValueRenderer.normalizePhysical(ColumnType.DATETIME, null, null, "current_timestamp"))
            .isEqualTo("CURRENT_TIMESTAMP");
        assertThatThrownBy(() -> DefaultValueRenderer.normalizePhysical(ColumnType.BOOLEAN, null, null, "2"))
            .isInstanceOf(BaasBadRequestException.class);
        assertThatThrownBy(() -> DefaultValueRenderer.normalizePhysical(ColumnType.DATETIME, null, null,
                "2026-02-30 12:00:00"))
            .isInstanceOf(BaasBadRequestException.class);
    }

}
