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

package com.aiwork.baas.provision;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class PhysicalPreconditionsHealthIndicatorTest {

    @Test
    void reportsUpWhenPageSizeMatches() {
        var indicator = new PhysicalPreconditionsHealthIndicator(new PhysicalPreconditions(() -> 16384L));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void reportsDownWithoutLeakingQueryError() {
        var indicator = new PhysicalPreconditionsHealthIndicator(new PhysicalPreconditions(() -> {
            throw new IllegalStateException("secret jdbc endpoint");
        }));

        var health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().toString()).doesNotContain("secret jdbc endpoint");
    }

    @Test
    void refreshesEveryProbeAndReportsRuntimePageSizeDriftDown() {
        AtomicLong pageSize = new AtomicLong(16384L);
        var indicator = new PhysicalPreconditionsHealthIndicator(new PhysicalPreconditions(pageSize::get));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        pageSize.set(8192L);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

}
