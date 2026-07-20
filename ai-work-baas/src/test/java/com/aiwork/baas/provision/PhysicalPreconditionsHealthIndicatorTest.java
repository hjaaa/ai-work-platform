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

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.availability.ApplicationAvailabilityAutoConfiguration;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration;
import org.springframework.boot.health.autoconfigure.application.AvailabilityHealthContributorAutoConfiguration;
import org.springframework.boot.health.autoconfigure.contributor.HealthContributorAutoConfiguration;
import org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class PhysicalPreconditionsHealthIndicatorTest {

    private final ApplicationContextRunner readinessContextRunner = new ApplicationContextRunner()
        .withInitializer(PhysicalPreconditionsHealthIndicatorTest::loadApplicationYaml)
        .withConfiguration(AutoConfigurations.of(ApplicationAvailabilityAutoConfiguration.class,
                HealthContributorRegistryAutoConfiguration.class,
                HealthContributorAutoConfiguration.class, AvailabilityHealthContributorAutoConfiguration.class,
                HealthEndpointAutoConfiguration.class))
        .withUserConfiguration(FailingPhysicalPreconditionsConfiguration.class)
        .withPropertyValues("management.endpoint.health.access=UNRESTRICTED");

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

    @Test
    void readinessGroupReportsDownWhenPhysicalPreconditionsFail() {
        readinessContextRunner.run(context -> {
            HealthEndpoint endpoint = context.getBean(HealthEndpoint.class);

            assertThat(endpoint.healthForPath("readiness").getStatus()).isEqualTo(Status.DOWN);
        });
    }

    private static void loadApplicationYaml(ConfigurableApplicationContext context) {
        try {
            Resource resource = context.getResource("classpath:application.yml");
            var propertySource = new YamlPropertySourceLoader().load("baasApplication", resource).get(0);
            context.getEnvironment().getPropertySources().addFirst(propertySource);
        }
        catch (IOException exception) {
            throw new IllegalStateException("failed to load application.yml", exception);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FailingPhysicalPreconditionsConfiguration {

        @Bean
        PhysicalPreconditions physicalPreconditions() {
            return new PhysicalPreconditions(() -> 8192L);
        }

        @Bean
        PhysicalPreconditionsHealthIndicator physicalPreconditionsHealthIndicator(
                PhysicalPreconditions physicalPreconditions) {
            return new PhysicalPreconditionsHealthIndicator(physicalPreconditions);
        }

    }

}
