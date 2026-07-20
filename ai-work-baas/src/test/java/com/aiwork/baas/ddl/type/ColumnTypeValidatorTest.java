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

import com.aiwork.baas.exception.BaasBadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ColumnTypeValidatorTest {

    @ParameterizedTest
    @CsvSource({ "bigint,,", "int,,", "text,,", "boolean,,", "date,,", "datetime,,", "json,,", "varchar,1,",
            "varchar,768,", "varchar,4096,", "decimal,1,0", "decimal,65,30", "decimal,65,", "decimal,31,30" })
    void acceptsValidTypeParams(String dataType, Integer length, Integer scale) {
        assertThat(ColumnTypeValidator.validateTypeParams(dataType, length, scale)).isNotNull();
    }

    @ParameterizedTest
    @CsvSource({ "int,11,", "bigint,20,", "int,,2", "varchar,,", "varchar,0,", "varchar,4097,", "varchar,10,2",
            "decimal,,", "decimal,0,0", "decimal,66,0", "decimal,10,31", "decimal,10,11", "date,10,",
            "datetime,,6", "text,255,", "json,1,", "boolean,1," })
    void rejectsInvalidTypeParams(String dataType, Integer length, Integer scale) {
        assertThatThrownBy(() -> ColumnTypeValidator.validateTypeParams(dataType, length, scale))
            .isInstanceOf(BaasBadRequestException.class);
    }

    @Test
    void rejectsUnknownType() {
        assertThatThrownBy(() -> ColumnTypeValidator.validateTypeParams("float", null, null))
            .isInstanceOf(BaasBadRequestException.class);
        assertThatThrownBy(() -> ColumnTypeValidator.validateTypeParams("tinyint", null, null))
            .isInstanceOf(BaasBadRequestException.class);
    }

    @Test
    void decimalScaleDefaultsToZero() {
        assertThat(ColumnTypeValidator.validateTypeParams("decimal", 10, null)).isEqualTo(ColumnType.DECIMAL);
    }

}
