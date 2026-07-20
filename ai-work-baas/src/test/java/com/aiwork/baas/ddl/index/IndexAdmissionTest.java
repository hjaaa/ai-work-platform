/*
 *
 *      Copyright (c) 2018-2026, lengleng All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice,
 *  this list of conditions and the following disclaimer.
 *  Redistributions in binary form must reproduce the above copyright
 *  notice, this list of conditions and the following disclaimer in the
 *  documentation and/or other materials provided with the distribution.
 *  Neither the name of the pig4cloud.com developer nor the names of its
 *  contributors may be used to endorse or promote products derived from
 *  this software without specific prior written permission.
 *  Author: lengleng (wangiegie@gmail.com)
 *
 */

package com.aiwork.baas.ddl.index;

import com.aiwork.baas.ddl.type.ColumnType;
import com.aiwork.baas.ddl.type.LogicalColumn;
import com.aiwork.baas.exception.BaasBadRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndexAdmissionTest {

    @Test
    void textAndJsonIndexesRejected() {
        assertThatThrownBy(() -> IndexAdmission.validateColumnIndexRequest(ColumnType.TEXT, null, false, true))
            .isInstanceOf(BaasBadRequestException.class);
        assertThatThrownBy(() -> IndexAdmission.validateColumnIndexRequest(ColumnType.JSON, null, true, false))
            .isInstanceOf(BaasBadRequestException.class);
        assertThatCode(() -> IndexAdmission.validateColumnIndexRequest(ColumnType.TEXT, null, false, false))
            .doesNotThrowAnyException();
    }

    @Test
    void varcharKeyLengthBoundaryAt768() {
        assertThatCode(() -> IndexAdmission.validateColumnIndexRequest(ColumnType.VARCHAR, 768, false, true))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> IndexAdmission.validateColumnIndexRequest(ColumnType.VARCHAR, 769, false, true))
            .isInstanceOf(BaasBadRequestException.class);
        assertThatThrownBy(() -> IndexAdmission.validateColumnIndexRequest(ColumnType.VARCHAR, 769, true, false))
            .isInstanceOf(BaasBadRequestException.class);
        assertThatCode(() -> IndexAdmission.validateColumnIndexRequest(ColumnType.VARCHAR, 4096, false, false))
            .doesNotThrowAnyException();
    }

    @Test
    void finalStructureRevalidatesEverythingIncludingSecondaryCount() {
        LogicalColumn indexedWide = new LogicalColumn("wide", ColumnType.VARCHAR, 769, null, true, null, false,
                false, false, true, null);
        assertThatThrownBy(() -> IndexAdmission.validateFinalStructure(List.of(indexedWide), 1))
            .isInstanceOf(BaasBadRequestException.class);

        LogicalColumn plain = new LogicalColumn("ok", ColumnType.INT, null, null, true, null, false, false, false,
                false, null);
        assertThatCode(() -> IndexAdmission.validateFinalStructure(List.of(plain), 64)).doesNotThrowAnyException();
        assertThatThrownBy(() -> IndexAdmission.validateFinalStructure(List.of(plain), 65))
            .isInstanceOf(BaasBadRequestException.class);
    }

    @Test
    void totalIndexLimitCountsPrimarySeparately() {
        assertThatCode(() -> IndexAdmission.validateTotalIndexCount(64)).doesNotThrowAnyException();
        assertThatThrownBy(() -> IndexAdmission.validateTotalIndexCount(65))
            .isInstanceOf(BaasBadRequestException.class);
    }

}
