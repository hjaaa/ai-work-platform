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
import com.aiwork.baas.datasource.ProjectDataSourceRegistry;
import com.aiwork.baas.entity.BaasApiKey;
import com.aiwork.baas.entity.BaasAuditLog;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.enums.ProjectStatus;
import com.aiwork.baas.entity.enums.ProvisionStep;
import com.aiwork.baas.exception.ProjectProvisionException;
import com.aiwork.baas.mapper.BaasApiKeyMapper;
import com.aiwork.baas.mapper.BaasAuditLogMapper;
import com.aiwork.baas.mapper.BaasProjectMapper;
import com.aiwork.baas.provision.ProjectProvisioner;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

@SpringBootTest(classes = LifecycleTestApplication.class, properties = { "spring.config.import=",
        "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false" })
@Testcontainers
class ProjectLifecycleServiceTest {

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
    private BaasProjectMapper projectMapper;

    @Autowired
    private BaasApiKeyMapper apiKeyMapper;

    @MockitoSpyBean
    private BaasAuditLogMapper auditLogMapper;

    @MockitoSpyBean
    private ProjectProvisioner provisioner;

    @MockitoSpyBean
    private ProjectDataSourceRegistry registry;

    @Autowired
    private ProjectCleanupJob cleanupJob;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private JdbcTemplate rootJdbc() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                mysql.getJdbcUrl().replace("/ai_work_baas", "/mysql"), "root", "root");
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        return new JdbcTemplate(dataSource);
    }

    @Test
    void createProjectEndsActiveWithKeysAndSystemTables() {
        var created = lifecycleService.createProject("demo", 1L);

        assertThat(created.project().getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(created.publishableKey()).startsWith("pub_");
        assertThat(created.secretKey()).startsWith("sec_");
        Long tableCount = rootJdbc().queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? "
                        + "AND table_name IN ('_users','_sessions','_refresh_tokens')",
                Long.class, created.project().getDbName());
        assertThat(tableCount).isEqualTo(3L);
    }

    @Test
    void createProjectRejectsAmbientTransactionWithoutSideEffects() {
        Long projectCountBefore = projectMapper.selectCount(null);
        Mockito.clearInvocations(provisioner, registry);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
                status -> lifecycleService.createProject("ambientcreate", 1L)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("active transaction");

        assertThat(projectMapper.selectCount(null)).isEqualTo(projectCountBefore);
        Mockito.verifyNoInteractions(provisioner, registry);
    }

    @Test
    void realProvisionFailureThenRetryEndsActive() {
        Mockito.doThrow(new IllegalStateException("mysql gone"))
            .when(provisioner)
            .initSystemTables(Mockito.anyString());
        try {
            assertThatThrownBy(() -> lifecycleService.createProject("retrycase", 1L))
                .isInstanceOf(ProjectProvisionException.class);
        }
        finally {
            Mockito.reset(provisioner);
        }

        BaasProject failedProject = projectMapper.selectOne(Wrappers.<BaasProject>lambdaQuery()
            .eq(BaasProject::getName, "retrycase"));
        assertThat(failedProject.getStatus()).isEqualTo(ProjectStatus.FAILED);
        assertThat(failedProject.getProvisionStep()).isEqualTo(ProvisionStep.USER_CREATED.name());

        var retried = lifecycleService.retryProvision(failedProject.getId());
        assertThat(retried.project().getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        Long activeKeyCount = apiKeyMapper.selectCount(Wrappers.<BaasApiKey>lambdaQuery()
            .eq(BaasApiKey::getProjectId, failedProject.getId())
            .eq(BaasApiKey::getStatus, "ACTIVE"));
        assertThat(activeKeyCount).isEqualTo(2L);
    }

    @Test
    void failedAuditDoesNotMaskProvisionFailureAndProjectRemainsFailed() {
        Mockito.doThrow(new IllegalStateException("mysql gone"))
            .when(provisioner)
            .initSystemTables(Mockito.anyString());
        Mockito.doThrow(new IllegalStateException("audit unavailable"))
            .when(auditLogMapper)
            .insert(Mockito.any(BaasAuditLog.class));
        try {
            Throwable thrown = catchThrowable(() -> lifecycleService.createProject("auditfailure", 1L));
            assertThat(thrown)
                .isInstanceOf(ProjectProvisionException.class)
                .hasMessageContaining("provision failed at USER_CREATED");
            assertThat(thrown.getCause()).hasMessage("mysql gone");
        }
        finally {
            Mockito.reset(provisioner, auditLogMapper);
        }

        BaasProject failedProject = projectMapper.selectOne(Wrappers.<BaasProject>lambdaQuery()
            .eq(BaasProject::getName, "auditfailure"));
        assertThat(failedProject.getStatus()).isEqualTo(ProjectStatus.FAILED);
    }

    @Test
    void createAuditFailureRollsBackCompletionTransactionAndRetrySucceeds() {
        JdbcTemplate rootJdbc = rootJdbc();
        rootJdbc.execute("DROP TRIGGER IF EXISTS ai_work_baas.reject_project_create_audit");
        rootJdbc.execute("CREATE TRIGGER ai_work_baas.reject_project_create_audit "
                + "BEFORE INSERT ON ai_work_baas.baas_audit_log FOR EACH ROW "
                + "BEGIN IF NEW.action = 'PROJECT_CREATE' THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'create audit unavailable'; "
                + "END IF; END");
        try {
            assertThatThrownBy(() -> lifecycleService.createProject("createauditrollback", 1L))
                .isInstanceOf(ProjectProvisionException.class)
                .hasMessageContaining("provision failed");

            BaasProject failedProject = projectMapper.selectOne(Wrappers.<BaasProject>lambdaQuery()
                .eq(BaasProject::getName, "createauditrollback"));
            assertThat(failedProject.getStatus()).isEqualTo(ProjectStatus.FAILED);
            assertThat(failedProject.getProvisionStep()).isEqualTo(ProvisionStep.JWT_KEY.name());
            Long activeKeyCount = apiKeyMapper.selectCount(Wrappers.<BaasApiKey>lambdaQuery()
                .eq(BaasApiKey::getProjectId, failedProject.getId())
                .eq(BaasApiKey::getStatus, "ACTIVE"));
            assertThat(activeKeyCount).isZero();
            Long failureAuditCount = auditLogMapper.selectCount(Wrappers.<BaasAuditLog>lambdaQuery()
                .eq(BaasAuditLog::getProjectId, failedProject.getId())
                .eq(BaasAuditLog::getAction, "PROJECT_PROVISION_FAILED"));
            assertThat(failureAuditCount).isEqualTo(1L);
        }
        finally {
            rootJdbc.execute("DROP TRIGGER IF EXISTS ai_work_baas.reject_project_create_audit");
        }

        BaasProject failedProject = projectMapper.selectOne(Wrappers.<BaasProject>lambdaQuery()
            .eq(BaasProject::getName, "createauditrollback"));
        var retried = lifecycleService.retryProvision(failedProject.getId());
        assertThat(retried.project().getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        Long activeKeyCount = apiKeyMapper.selectCount(Wrappers.<BaasApiKey>lambdaQuery()
            .eq(BaasApiKey::getProjectId, failedProject.getId())
            .eq(BaasApiKey::getStatus, "ACTIVE"));
        assertThat(activeKeyCount).isEqualTo(2L);
    }

    @Test
    void retryRevokesLeftoverActiveKeysAndReissues() {
        var created = lifecycleService.createProject("rekeys", 1L);
        Long projectId = created.project().getId();
        Long oldKeyId = apiKeyMapper.selectList(Wrappers.<BaasApiKey>lambdaQuery()
            .eq(BaasApiKey::getProjectId, projectId)).get(0).getId();
        projectMapper.update(null, Wrappers.<BaasProject>lambdaUpdate()
            .eq(BaasProject::getId, projectId)
            .set(BaasProject::getStatus, ProjectStatus.FAILED)
            .set(BaasProject::getProvisionStep, ProvisionStep.JWT_KEY.name()));

        var retried = lifecycleService.retryProvision(projectId);

        assertThat(retried.project().getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(apiKeyMapper.selectById(oldKeyId).getStatus()).isEqualTo("REVOKED");
        assertThat(retried.publishableKey()).isNotEqualTo(created.publishableKey());
        Long activeKeyCount = apiKeyMapper.selectCount(Wrappers.<BaasApiKey>lambdaQuery()
            .eq(BaasApiKey::getProjectId, projectId)
            .eq(BaasApiKey::getStatus, "ACTIVE"));
        assertThat(activeKeyCount).isEqualTo(2L);
    }

    @Test
    void advanceDoesNotOverwriteConcurrentProjectFieldUpdate() {
        var created = lifecycleService.createProject("narrowadvance", 1L);
        Long projectId = created.project().getId();
        projectMapper.update(null, Wrappers.<BaasProject>lambdaUpdate()
            .eq(BaasProject::getId, projectId)
            .set(BaasProject::getAllowedOrigins, "[\"https://old.example\"]")
            .set(BaasProject::getStatus, ProjectStatus.FAILED)
            .set(BaasProject::getProvisionStep, ProvisionStep.DB_CREATED.name()));
        Mockito.doAnswer(invocation -> {
            projectMapper.update(null, Wrappers.<BaasProject>lambdaUpdate()
                .eq(BaasProject::getId, projectId)
                .set(BaasProject::getAllowedOrigins, "[\"https://new.example\"]"));
            invocation.callRealMethod();
            return null;
        })
            .when(provisioner)
            .createRuntimeUser(Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
        try {
            lifecycleService.retryProvision(projectId);
        }
        finally {
            Mockito.reset(provisioner);
        }

        assertThat(projectMapper.selectById(projectId).getAllowedOrigins())
            .isEqualTo("[\"https://new.example\"]");
    }

    @Test
    void deleteProjectBlocksRevokesAndSchedulesCleanup() {
        var created = lifecycleService.createProject("todel", 1L);

        lifecycleService.deleteProject(created.project().getId(), 1L);

        BaasProject deletingProject = projectMapper.selectById(created.project().getId());
        assertThat(deletingProject.getStatus()).isEqualTo(ProjectStatus.DELETING);
        assertThat(deletingProject.getDeleteAfter()).isAfter(LocalDateTime.now().plusDays(6));
        Long activeKeyCount = apiKeyMapper.selectCount(Wrappers.<BaasApiKey>lambdaQuery()
            .eq(BaasApiKey::getProjectId, deletingProject.getId())
            .eq(BaasApiKey::getStatus, "ACTIVE"));
        assertThat(activeKeyCount).isZero();
    }

    @Test
    void deleteProjectRejectsAmbientTransactionWithoutSideEffects() {
        var created = lifecycleService.createProject("ambientdelete", 1L);
        Long projectId = created.project().getId();
        Mockito.clearInvocations(provisioner, registry);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
                status -> lifecycleService.deleteProject(projectId, 1L)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("active transaction");

        assertThat(projectMapper.selectById(projectId).getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        Long activeKeyCount = apiKeyMapper.selectCount(Wrappers.<BaasApiKey>lambdaQuery()
            .eq(BaasApiKey::getProjectId, projectId)
            .eq(BaasApiKey::getStatus, "ACTIVE"));
        assertThat(activeKeyCount).isEqualTo(2L);
        Mockito.verifyNoInteractions(provisioner, registry);
    }

    @Test
    void deleteIsReentrantForCompensation() {
        var created = lifecycleService.createProject("redel", 1L);
        lifecycleService.deleteProject(created.project().getId(), 1L);
        BaasProject firstDelete = projectMapper.selectById(created.project().getId());

        lifecycleService.deleteProject(created.project().getId(), 1L);

        BaasProject secondDelete = projectMapper.selectById(created.project().getId());
        assertThat(secondDelete.getStatus()).isEqualTo(ProjectStatus.DELETING);
        assertThat(secondDelete.getDeleteAfter()).isEqualTo(firstDelete.getDeleteAfter());
        Long deleteAuditCount = auditLogMapper.selectCount(Wrappers.<BaasAuditLog>lambdaQuery()
            .eq(BaasAuditLog::getProjectId, created.project().getId())
            .eq(BaasAuditLog::getAction, "PROJECT_DELETE"));
        assertThat(deleteAuditCount).isEqualTo(1L);
    }

    @Test
    void registryFailureHappensAfterDeleteCommitAndReentryCompensates() {
        var created = lifecycleService.createProject("registryretry", 1L);
        String projectRef = created.project().getProjectRef();
        Mockito.doThrow(new IllegalStateException("registry unavailable"))
            .doCallRealMethod()
            .when(registry)
            .blockAndDrain(projectRef);
        try {
            assertThatThrownBy(() -> lifecycleService.deleteProject(created.project().getId(), 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("registry unavailable");

            BaasProject committedDelete = projectMapper.selectById(created.project().getId());
            assertThat(committedDelete.getStatus()).isEqualTo(ProjectStatus.DELETING);
            assertThat(committedDelete.getDeleteAfter()).isNotNull();
            Long activeKeyCount = apiKeyMapper.selectCount(Wrappers.<BaasApiKey>lambdaQuery()
                .eq(BaasApiKey::getProjectId, committedDelete.getId())
                .eq(BaasApiKey::getStatus, "ACTIVE"));
            assertThat(activeKeyCount).isZero();

            lifecycleService.deleteProject(created.project().getId(), 1L);

            BaasProject compensatedDelete = projectMapper.selectById(created.project().getId());
            assertThat(compensatedDelete.getDeleteAfter()).isEqualTo(committedDelete.getDeleteAfter());
            Long deleteAuditCount = auditLogMapper.selectCount(Wrappers.<BaasAuditLog>lambdaQuery()
                .eq(BaasAuditLog::getProjectId, created.project().getId())
                .eq(BaasAuditLog::getAction, "PROJECT_DELETE"));
            assertThat(deleteAuditCount).isEqualTo(1L);
            Mockito.verify(registry, Mockito.times(2)).blockAndDrain(projectRef);
        }
        finally {
            Mockito.reset(registry);
        }
    }

    @Test
    void retryOnDeletingProjectFails() {
        var created = lifecycleService.createProject("noretry", 1L);
        lifecycleService.deleteProject(created.project().getId(), 1L);

        assertThatThrownBy(() -> lifecycleService.retryProvision(created.project().getId()))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void physicalCleanupDropsDatabase() {
        var created = lifecycleService.createProject("tocleanup", 1L);
        lifecycleService.deleteProject(created.project().getId(), 1L);

        lifecycleService.physicallyCleanup(projectMapper.selectById(created.project().getId()));

        BaasProject deletedProject = projectMapper.selectById(created.project().getId());
        assertThat(deletedProject.getStatus()).isEqualTo(ProjectStatus.DELETED);
        Long databaseCount = rootJdbc().queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?", Long.class,
                created.project().getDbName());
        assertThat(databaseCount).isZero();
    }

    @Test
    void cleanupJobOnlyCleansExpiredDeletingProjects() {
        var expired = lifecycleService.createProject("expiredcleanup", 1L);
        var future = lifecycleService.createProject("futurecleanup", 1L);
        lifecycleService.deleteProject(expired.project().getId(), 1L);
        lifecycleService.deleteProject(future.project().getId(), 1L);
        projectMapper.update(null, Wrappers.<BaasProject>lambdaUpdate()
            .eq(BaasProject::getId, expired.project().getId())
            .set(BaasProject::getDeleteAfter, LocalDateTime.now().minusMinutes(1)));

        cleanupJob.cleanup();

        assertThat(projectMapper.selectById(expired.project().getId()).getStatus()).isEqualTo(ProjectStatus.DELETED);
        assertThat(projectMapper.selectById(future.project().getId()).getStatus()).isEqualTo(ProjectStatus.DELETING);
        Long futureDatabaseCount = rootJdbc().queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?", Long.class,
                future.project().getDbName());
        assertThat(futureDatabaseCount).isEqualTo(1L);
    }

}
