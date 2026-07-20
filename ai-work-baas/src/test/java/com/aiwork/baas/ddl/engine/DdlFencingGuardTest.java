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

package com.aiwork.baas.ddl.engine;

import com.aiwork.baas.LifecycleTestApplication;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.enums.ProjectStatus;
import com.aiwork.baas.mapper.BaasProjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 项目级 fencing 守卫测试。
 *
 * @author ai-work
 * @date 2026/07/18
 */
@SpringBootTest(classes = LifecycleTestApplication.class, properties = { "spring.config.import=",
        "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false" })
@Testcontainers
class DdlFencingGuardTest {

    @Container
    static MySQLContainer mysql = new MySQLContainer("mysql:8.4").withUsername("root")
        .withPassword("root")
        .withDatabaseName("ai_work_baas")
        .withInitScript("init-metadata.sql");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "root");
        registry.add("baas.provisioner.url", () -> mysql.getJdbcUrl().replace("/ai_work_baas", "/mysql"));
        registry.add("baas.provisioner.username", () -> "root");
        registry.add("baas.provisioner.password", () -> "root");
        registry.add("baas.project-db.host", mysql::getHost);
        registry.add("baas.project-db.port", () -> mysql.getMappedPort(3306));
        registry.add("server.servlet.context-path", () -> "");
    }

    @Autowired
    private DdlFencingGuard fencingGuard;

    @Autowired
    private BaasProjectMapper projectMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long newProjectId() {
        BaasProject project = new BaasProject();
        project.setProjectRef(UUID.randomUUID().toString().substring(0, 16));
        project.setName("guard");
        project.setDbName("baas_x");
        project.setStatus(ProjectStatus.ACTIVE);
        project.setOwnerUserId(1L);
        projectMapper.insert(project);
        return project.getId();
    }

    @Test
    void incrementReturnsNewEpochAndPersists() {
        Long projectId = newProjectId();
        long first = transactionTemplate.execute(status -> fencingGuard.incrementEpochInTx(projectId));
        long second = transactionTemplate.execute(status -> fencingGuard.incrementEpochInTx(projectId));

        assertThat(first).isEqualTo(1L);
        assertThat(second).isEqualTo(2L);
        assertThat(projectMapper.selectById(projectId).getDdlFenceEpoch()).isEqualTo(2L);
    }

    @Test
    void verifyPassesOnMatchingEpochAndThrowsOnStale() {
        Long projectId = newProjectId();
        long epoch = transactionTemplate.execute(status -> fencingGuard.incrementEpochInTx(projectId));

        transactionTemplate.executeWithoutResult(
                status -> assertThat(fencingGuard.verifyEpochInTx(projectId, epoch).getId()).isEqualTo(projectId));

        transactionTemplate.execute(status -> fencingGuard.incrementEpochInTx(projectId));
        assertThatThrownBy(() -> transactionTemplate
            .executeWithoutResult(status -> fencingGuard.verifyEpochInTx(projectId, epoch)))
            .isInstanceOf(StaleExecutorException.class);
    }

    @Test
    void rollbackRevertsEpochIncrement() {
        Long projectId = newProjectId();
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            fencingGuard.incrementEpochInTx(projectId);
            throw new IllegalStateException("simulated branch cas failure");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(projectMapper.selectById(projectId).getDdlFenceEpoch()).isZero();
    }

}
