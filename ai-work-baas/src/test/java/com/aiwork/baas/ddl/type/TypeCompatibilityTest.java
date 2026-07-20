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

package com.aiwork.baas.ddl.type;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TypeCompatibilityTest {

    private LogicalColumn col(ColumnType type, Integer length, Integer scale, boolean nullable) {
        return new LogicalColumn("c", type, length, scale, nullable, null, false, false, false, false, null);
    }

    @Test
    void decimalScaleIsNormalizedToZero() {
        assertThat(col(ColumnType.DECIMAL, 10, null, true).scale()).isZero();
    }

    @Test
    void losslessSetPerSpec13() {
        assertThat(TypeCompatibility.isLossless(col(ColumnType.INT, null, null, true),
                col(ColumnType.BIGINT, null, null, true))).isTrue();
        assertThat(TypeCompatibility.isLossless(col(ColumnType.VARCHAR, 10, null, true),
                col(ColumnType.VARCHAR, 20, null, true))).isTrue();
        assertThat(TypeCompatibility.isLossless(col(ColumnType.VARCHAR, 10, null, true),
                col(ColumnType.TEXT, null, null, true))).isTrue();
        assertThat(TypeCompatibility.isLossless(col(ColumnType.DECIMAL, 10, 2, true),
                col(ColumnType.DECIMAL, 12, 3, true))).isTrue();
        assertThat(TypeCompatibility.isLossless(col(ColumnType.DATE, null, null, true),
                col(ColumnType.DATETIME, null, null, true))).isTrue();
        assertThat(TypeCompatibility.isLossless(col(ColumnType.INT, null, null, false),
                col(ColumnType.INT, null, null, true))).isTrue();
        assertThat(TypeCompatibility.isLossless(col(ColumnType.VARCHAR, 10, null, true),
                col(ColumnType.VARCHAR, 10, null, true))).isTrue();
    }

    @Test
    void lossyConversionsDetected() {
        assertThat(TypeCompatibility.isLossless(col(ColumnType.BIGINT, null, null, true),
                col(ColumnType.INT, null, null, true))).isFalse();
        assertThat(TypeCompatibility.isLossless(col(ColumnType.VARCHAR, 20, null, true),
                col(ColumnType.VARCHAR, 10, null, true))).isFalse();
        assertThat(TypeCompatibility.isLossless(col(ColumnType.TEXT, null, null, true),
                col(ColumnType.VARCHAR, 4096, null, true))).isFalse();
        assertThat(TypeCompatibility.isLossless(col(ColumnType.VARCHAR, 10, null, true),
                col(ColumnType.INT, null, null, true))).isFalse();
        assertThat(TypeCompatibility.isLossless(col(ColumnType.DECIMAL, 10, 2, true),
                col(ColumnType.DECIMAL, 10, 3, true))).isFalse();
        assertThat(TypeCompatibility.isLossless(col(ColumnType.DECIMAL, 10, 2, true),
                col(ColumnType.DECIMAL, 9, 2, true))).isFalse();
        assertThat(TypeCompatibility.isLossless(col(ColumnType.DATETIME, null, null, true),
                col(ColumnType.DATE, null, null, true))).isFalse();
        assertThat(TypeCompatibility.isLossless(col(ColumnType.INT, null, null, true),
                col(ColumnType.INT, null, null, false))).isFalse();
        assertThat(TypeCompatibility.isLossless(col(ColumnType.BOOLEAN, null, null, true),
                col(ColumnType.INT, null, null, true))).isFalse();
    }

}
