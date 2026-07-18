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
