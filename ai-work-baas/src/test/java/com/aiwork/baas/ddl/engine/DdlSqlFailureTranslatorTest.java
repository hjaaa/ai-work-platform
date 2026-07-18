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

package com.aiwork.baas.ddl.engine;

import com.aiwork.baas.exception.DdlConflictException;
import com.aiwork.baas.exception.DdlExecutionException;
import com.aiwork.baas.exception.BaasStudioExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.BadSqlGrammarException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class DdlSqlFailureTranslatorTest {

    @Test
    void sqlStateDataAndIntegrityClassesMapToConflict() {
        RuntimeException translated = DdlSqlFailureTranslator.translate(
                new DataIntegrityViolationException("raw", new SQLException("raw", "23000", 1062)));

        assertThat(translated).isInstanceOf(DdlConflictException.class)
            .hasMessage("DDL 与现有数据不兼容");
    }

    @Test
    void unknownSqlFailureKeepsOnlyStructuredDiagnostics() {
        RuntimeException translated = DdlSqlFailureTranslator.translate(
                new BadSqlGrammarException("task", "secret sql", new SQLException("raw", "HY000", 9999)));

        assertThat(translated).isInstanceOf(DdlExecutionException.class);
        DdlExecutionException failure = (DdlExecutionException) translated;
        assertThat(failure.getMessage()).isEqualTo("DDL 执行失败");
        assertThat(failure.sqlState()).isEqualTo("HY000");
        assertThat(failure.vendorCode()).isEqualTo(9999);
    }

    @Test
    void studioHandlerReturnsFixedMessageWithoutUsingExceptionDetails() {
        DdlExecutionException failure = new DdlExecutionException("SECRET_CODE", "HY000", 9999);

        assertThat(new BaasStudioExceptionHandler().handleDdlExecutionFailure().getMsg()).isEqualTo("DDL 执行失败");
    }

}
