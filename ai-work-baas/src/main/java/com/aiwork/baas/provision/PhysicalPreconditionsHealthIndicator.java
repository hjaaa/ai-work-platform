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

package com.aiwork.baas.provision;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Provisioner 物理前置 readiness(spec §9.1)。DOWN 只公开稳定原因码，不透传 JDBC 异常。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public final class PhysicalPreconditionsHealthIndicator implements HealthIndicator {

    private final PhysicalPreconditions physicalPreconditions;

    public PhysicalPreconditionsHealthIndicator(PhysicalPreconditions physicalPreconditions) {
        this.physicalPreconditions = physicalPreconditions;
    }

    @Override
    public Health health() {
        try {
            physicalPreconditions.refresh();
            physicalPreconditions.assertSatisfied();
            return Health.up().withDetail("baasPhysicalPreconditions", "satisfied").build();
        }
        catch (RuntimeException exception) {
            return Health.down().withDetail("baasPhysicalPreconditions", "failed").build();
        }
    }

}
