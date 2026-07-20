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

package com.aiwork.baas.ddl;

import com.aiwork.baas.exception.BaasBadRequestException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationIdValidatorTest {

    @Test
    void canonicalUuidAccepted() {
        assertThatCode(() -> OperationIdValidator.requireUuid(UUID.randomUUID().toString()))
            .doesNotThrowAnyException();
    }

    @Test
    void blankMalformedAndNonCanonicalUuidRejected() {
        assertThatThrownBy(() -> OperationIdValidator.requireUuid(" "))
            .isInstanceOf(BaasBadRequestException.class);
        assertThatThrownBy(() -> OperationIdValidator.requireUuid("operation-1"))
            .isInstanceOf(BaasBadRequestException.class);
        assertThatThrownBy(() -> OperationIdValidator.requireUuid("550E8400-E29B-41D4-A716-446655440000"))
            .isInstanceOf(BaasBadRequestException.class);
    }

}
