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
