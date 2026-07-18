package com.aiwork.baas.mapper;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DdlLogMapperContractTest {

    @Test
    void exposesPlanBOwnershipAndGuardMethods() throws Exception {
        Class<BaasDdlLogMapper> type = BaasDdlLogMapper.class;

        assertThat(type.getDeclaredMethod("casRetryFailed", Long.class, String.class, String.class, long.class))
            .isNotNull();
        assertThat(type.getDeclaredMethod("casTakeOverRunning", Long.class, String.class, String.class, long.class))
            .isNotNull();
        assertThat(type.getDeclaredMethod("casClaimPending", Long.class, String.class, long.class)).isNotNull();
        assertThat(type.getDeclaredMethod("advanceStepGuarded", Long.class, String.class, String.class)).isNotNull();
        assertThat(type.getDeclaredMethod("finishGuarded", Long.class, String.class, long.class, String.class,
                String.class, String.class, String.class)).isNotNull();
    }

}
