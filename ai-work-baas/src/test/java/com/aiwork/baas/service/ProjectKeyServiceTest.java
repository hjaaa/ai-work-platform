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

package com.aiwork.baas.service;

import com.aiwork.baas.LifecycleTestApplication;
import com.aiwork.baas.controller.dto.CreatedKeyVO;
import com.aiwork.baas.entity.BaasApiKey;
import com.aiwork.baas.entity.BaasAuditLog;
import com.aiwork.baas.entity.enums.KeyType;
import com.aiwork.baas.entity.enums.ProjectStatus;
import com.aiwork.baas.exception.ProjectNotFoundException;
import com.aiwork.baas.mapper.BaasApiKeyMapper;
import com.aiwork.baas.mapper.BaasAuditLogMapper;
import com.aiwork.baas.mapper.BaasProjectMapper;
import com.aiwork.baas.security.key.ApiKeyGenerator;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(classes = LifecycleTestApplication.class, properties = { "spring.config.import=",
        "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false" })
@Testcontainers
class ProjectKeyServiceTest {

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
    private ProjectLifecycleService lifecycleService;

    @Autowired
    private ProjectKeyService keyService;

    @Autowired
    private BaasApiKeyMapper apiKeyMapper;

    @Autowired
    private BaasProjectMapper projectMapper;

    @Autowired
    private ApiKeyGenerator keyGenerator;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private BaasAuditLogMapper auditLogMapper;

    @Test
    void createKeyPersistsHashAndReturnsPlaintextOnce() {
        var project = lifecycleService.createProject("keycreate", 1L);

        CreatedKeyVO createdKey = keyService.createKey(project.project().getId(), KeyType.SECRET, 1L);

        BaasApiKey storedKey = apiKeyMapper.selectById(Long.valueOf(createdKey.id()));
        assertThat(createdKey.keyType()).isEqualTo(KeyType.SECRET.name());
        assertThat(createdKey.plaintext()).startsWith("sec_");
        assertThat(keyGenerator.matches(createdKey.plaintext(), storedKey.getKeyHash())).isTrue();
        assertThat(storedKey.getKeyPrefix()).isEqualTo(createdKey.plaintext().substring(0, 12));
    }

    @Test
    void listKeysReturnsSafeMetadataOnly() {
        var project = lifecycleService.createProject("keylist", 1L);

        var keys = keyService.listKeys(project.project().getId());

        assertThat(keys).hasSize(2).allSatisfy(key -> {
            assertThat(key.id()).matches("\\d+");
            assertThat(key.keyPrefix()).matches("(pub|sec)_.+");
            assertThat(key.status()).isEqualTo("ACTIVE");
        });
    }

    @Test
    void updateAllowedOriginsStoresJsonArray() throws JsonProcessingException {
        var project = lifecycleService.createProject("originupdate", 1L);

        keyService.updateAllowedOrigins(project.project().getId(),
                List.of("https://a.example", "https://b.example"));

        String storedOrigins = projectMapper.selectById(project.project().getId()).getAllowedOrigins();
        assertThat(new ObjectMapper().readValue(storedOrigins, new TypeReference<List<String>>() {
        })).containsExactly("https://a.example", "https://b.example");
    }

    @Test
    void revokeWithWrongProjectIdIsRejected() {
        var firstProject = lifecycleService.createProject("wrongscopea", 1L);
        var secondProject = lifecycleService.createProject("wrongscopeb", 1L);
        Long secondProjectKeyId = firstKeyId(secondProject.project().getId());

        assertThatThrownBy(() -> keyService.revokeKey(firstProject.project().getId(), secondProjectKeyId, 1L))
            .isInstanceOf(ProjectNotFoundException.class);
        assertThat(apiKeyMapper.selectById(secondProjectKeyId).getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void revokeWithCorrectProjectIdSucceeds() {
        var project = lifecycleService.createProject("correctscope", 1L);
        Long keyId = firstKeyId(project.project().getId());

        keyService.revokeKey(project.project().getId(), keyId, 1L);

        assertThat(apiKeyMapper.selectById(keyId).getStatus()).isEqualTo("REVOKED");
        assertThat(apiKeyMapper.selectById(keyId).getRevokeTime()).isNotNull();
    }

    @Test
    void createKeyOnDeletingProjectIsRejected() {
        var project = lifecycleService.createProject("deletingkey", 1L);
        lifecycleService.deleteProject(project.project().getId(), 1L);

        assertThatThrownBy(() -> keyService.createKey(project.project().getId(), KeyType.PUBLISHABLE, 1L))
            .isInstanceOf(ProjectNotFoundException.class);
        assertThat(activeKeyCount(project.project().getId())).isZero();
    }

    @Test
    void createKeyRejectsAmbientTransactionWithoutSideEffects() {
        var project = lifecycleService.createProject("ambientkeycreate", 1L);
        Long activeKeyCountBefore = activeKeyCount(project.project().getId());
        Long auditCountBefore = auditCount(project.project().getId(), "KEY_CREATE");

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                keyService.createKey(project.project().getId(), KeyType.SECRET, 1L)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("active transaction");

        assertThat(activeKeyCount(project.project().getId())).isEqualTo(activeKeyCountBefore);
        assertThat(auditCount(project.project().getId(), "KEY_CREATE")).isEqualTo(auditCountBefore);
    }

    @Test
    void revokeKeyRejectsAmbientTransactionWithoutSideEffects() {
        var project = lifecycleService.createProject("ambientkeyrevoke", 1L);
        Long keyId = firstKeyId(project.project().getId());
        Long auditCountBefore = auditCount(project.project().getId(), "KEY_REVOKE");

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                keyService.revokeKey(project.project().getId(), keyId, 1L)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("active transaction");

        assertThat(apiKeyMapper.selectById(keyId).getStatus()).isEqualTo("ACTIVE");
        assertThat(auditCount(project.project().getId(), "KEY_REVOKE")).isEqualTo(auditCountBefore);
    }

    @Test
    void createAuditFailureRollsBackKeyCreation() {
        var project = lifecycleService.createProject("createauditfailure", 1L);
        Long activeKeyCountBefore = activeKeyCount(project.project().getId());
        doThrow(new IllegalStateException("audit store down"))
            .when(auditLogMapper)
            .insert(any(BaasAuditLog.class));
        try {
            assertThatThrownBy(() -> keyService.createKey(project.project().getId(), KeyType.SECRET, 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit store down");
        }
        finally {
            Mockito.reset(auditLogMapper);
        }

        assertThat(activeKeyCount(project.project().getId())).isEqualTo(activeKeyCountBefore);
    }

    @Test
    void revokeAuditFailureRollsBackKeyRevocation() {
        var project = lifecycleService.createProject("revokeauditfailure", 1L);
        Long keyId = firstKeyId(project.project().getId());
        doThrow(new IllegalStateException("audit store down"))
            .when(auditLogMapper)
            .insert(any(BaasAuditLog.class));
        try {
            assertThatThrownBy(() -> keyService.revokeKey(project.project().getId(), keyId, 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit store down");
        }
        finally {
            Mockito.reset(auditLogMapper);
        }

        assertThat(apiKeyMapper.selectById(keyId).getStatus()).isEqualTo("ACTIVE");
        assertThat(apiKeyMapper.selectById(keyId).getRevokeTime()).isNull();
    }

    @Test
    void concurrentCreateAndDeleteSerializeOnProjectRowLock() throws Exception {
        var project = lifecycleService.createProject("keydeleterace", 1L);
        Long projectId = project.project().getId();
        CountDownLatch keyAuditReached = new CountDownLatch(1);
        CountDownLatch releaseKeyAudit = new CountDownLatch(1);
        doAnswer(invocation -> {
            BaasAuditLog auditLog = invocation.getArgument(0);
            if ("KEY_CREATE".equals(auditLog.getAction())) {
                keyAuditReached.countDown();
                if (!releaseKeyAudit.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting to release key audit");
                }
            }
            return jdbcTemplate.update(
                    "INSERT INTO baas_audit_log "
                            + "(project_id, operator_user_id, action, detail, level) VALUES (?, ?, ?, ?, ?)",
                    auditLog.getProjectId(), auditLog.getOperatorUserId(), auditLog.getAction(),
                    auditLog.getDetail(), auditLog.getLevel());
        }).when(auditLogMapper).insert(any(BaasAuditLog.class));

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        try {
            Future<CreatedKeyVO> createFuture = executorService.submit(
                    () -> keyService.createKey(projectId, KeyType.PUBLISHABLE, 1L));
            assertThat(keyAuditReached.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> deleteFuture = executorService.submit(() -> lifecycleService.deleteProject(projectId, 1L));
            awaitDataLockWait(Duration.ofSeconds(10));
            assertThat(deleteFuture).isNotDone();

            releaseKeyAudit.countDown();
            assertThat(createFuture.get(10, TimeUnit.SECONDS).plaintext()).startsWith("pub_");
            deleteFuture.get(10, TimeUnit.SECONDS);
        }
        finally {
            releaseKeyAudit.countDown();
            executorService.shutdownNow();
            Mockito.reset(auditLogMapper);
        }

        assertThat(projectMapper.selectById(projectId).getStatus()).isEqualTo(ProjectStatus.DELETING);
        assertThat(activeKeyCount(projectId)).isZero();
    }

    private Long firstKeyId(Long projectId) {
        return apiKeyMapper.selectList(Wrappers.<BaasApiKey>lambdaQuery()
            .eq(BaasApiKey::getProjectId, projectId))
            .get(0)
            .getId();
    }

    private Long activeKeyCount(Long projectId) {
        return apiKeyMapper.selectCount(Wrappers.<BaasApiKey>lambdaQuery()
            .eq(BaasApiKey::getProjectId, projectId)
            .eq(BaasApiKey::getStatus, "ACTIVE"));
    }

    private Long auditCount(Long projectId, String action) {
        return auditLogMapper.selectCount(Wrappers.<BaasAuditLog>lambdaQuery()
            .eq(BaasAuditLog::getProjectId, projectId)
            .eq(BaasAuditLog::getAction, action));
    }

    private void awaitDataLockWait(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Long waitCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM performance_schema.data_lock_waits", Long.class);
            if (waitCount != null && waitCount > 0) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("delete did not wait for the project row lock within " + timeout);
    }

}
