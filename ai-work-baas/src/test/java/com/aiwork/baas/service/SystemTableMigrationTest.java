/*
 *
 *      Copyright (c) 2018-2025, lengleng All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *  Redistributions of source code must retain the above copyright notice,
 *  this list of conditions and the following disclaimer.
 *
 */

package com.aiwork.baas.service;

import com.aiwork.baas.LifecycleTestApplication;
import com.aiwork.baas.controller.dto.ColumnDefinitionDTO;
import com.aiwork.baas.controller.dto.TableCreateDTO;
import com.aiwork.baas.ddl.engine.DdlFencingGuard;
import com.aiwork.baas.ddl.lock.DdlLockManager;
import com.aiwork.baas.ddl.lock.LockHandle;
import com.aiwork.baas.ddl.lock.ProjectDdlLockCallback;
import com.aiwork.baas.ddl.lock.ProjectDdlLockExecutor;
import com.aiwork.baas.entity.BaasApiKey;
import com.aiwork.baas.entity.BaasAuditLog;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.enums.ProjectStatus;
import com.aiwork.baas.entity.enums.ProvisionStep;
import com.aiwork.baas.exception.DdlConflictException;
import com.aiwork.baas.mapper.BaasApiKeyMapper;
import com.aiwork.baas.mapper.BaasAuditLogMapper;
import com.aiwork.baas.mapper.BaasProjectMapper;
import com.aiwork.baas.provision.LegacySystemTables;
import com.aiwork.baas.provision.SystemTableManifest;
import com.aiwork.baas.security.TestCurrentUserProvider;
import com.aiwork.baas.support.PlanBContainerSupport;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 系统表版本迁移的真实 MySQL/Redis 集成测试。
 *
 * @author ai-work
 * @date 2026/07/19
 */
@SpringBootTest(classes = LifecycleTestApplication.class,
        properties = { "spring.config.import=", "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false" })
class SystemTableMigrationTest extends PlanBContainerSupport {

    @Autowired
    private SystemTableMigrationService migrationService;

    @Autowired
    private ProjectLifecycleService lifecycleService;

    @Autowired
    private BaasProjectMapper projectMapper;

    @Autowired
    private TableManagementService tableService;

    @Autowired
    private DdlLockManager lockManager;

    @Autowired
    private BaasAuditLogMapper auditLogMapper;

    @Autowired
    private BaasApiKeyMapper apiKeyMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoSpyBean
    private ProjectDdlLockExecutor lockExecutor;

    @MockitoSpyBean
    private DdlFencingGuard fencingGuard;

    private JdbcTemplate rootJdbc;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", () -> MYSQL_USERNAME);
        registry.add("spring.datasource.password", () -> MYSQL_PASSWORD);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
        registry.add("baas.provisioner.url", () -> MYSQL.getJdbcUrl().replace("/ai_work_baas", "/mysql"));
        registry.add("baas.provisioner.username", () -> MYSQL_USERNAME);
        registry.add("baas.provisioner.password", () -> MYSQL_PASSWORD);
        registry.add("baas.project-db.host", MYSQL::getHost);
        registry.add("baas.project-db.port", () -> MYSQL.getMappedPort(3306));
        registry.add("server.servlet.context-path", () -> "");
    }

    @BeforeEach
    void setUpJdbc() {
        rootJdbc = new JdbcTemplate(mysqlDataSource());
    }

    @AfterEach
    void resetTestState() {
        TestCurrentUserProvider.userId = 1L;
        Mockito.reset(lockExecutor, fencingGuard);
        rootJdbc.execute("DROP TRIGGER IF EXISTS ai_work_baas.reject_migration_audit");
    }

    @Test
    void currentProjectReturnsFalseWithoutEpochChange() {
        BaasProject project = createProject("mig-cur");
        long epochBefore = reload(project).getDdlFenceEpoch();

        SystemTableMigrationResult result = migrationService.migrate(project);

        assertThat(result).isEqualTo(new SystemTableMigrationResult("ACTIVE", false));
        assertThat(reload(project).getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(reload(project).getDdlFenceEpoch()).isEqualTo(epochBefore);
        assertThat(migrationAudits(project)).isEmpty();
    }

    @Test
    void legacyProjectMigratedToSignedWithEpochParticipation() {
        BaasProject project = createProject("mig-leg");
        degradeToLegacy(project);
        rootJdbc.update("INSERT INTO `" + project.getDbName() + "`._users (email, password_hash) "
                + "VALUES ('a@b.c', 'h')");
        long epochBefore = reload(project).getDdlFenceEpoch();
        TestCurrentUserProvider.userId = 73L;

        SystemTableMigrationResult result = migrationService.migrate(project);
        SystemTableMigrationResult replay = migrationService.migrate(reload(project));

        assertThat(result).isEqualTo(new SystemTableMigrationResult("ACTIVE", true));
        assertThat(replay).isEqualTo(new SystemTableMigrationResult("ACTIVE", false));
        BaasProject reloaded = reload(project);
        assertThat(reloaded.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(reloaded.getDdlFenceEpoch()).isEqualTo(epochBefore + 5L);
        assertThat(idColumnType(project.getDbName(), "_users")).isEqualTo("bigint");
        assertThat(idColumnType(project.getDbName(), "_sessions")).isEqualTo("bigint");
        assertThat(idColumnType(project.getDbName(), "_refresh_tokens")).isEqualTo("bigint");
        assertThat(rootJdbc.queryForObject("SELECT COUNT(*) FROM `" + project.getDbName() + "`._users",
                Long.class)).isEqualTo(1L);
        assertAudit(migrationAudits(project), "SYSTEM_TABLE_MIGRATION_SUCCESS", 73L,
                "source=MANUAL,result=SUCCESS,migratedTables=3");
    }

    @Test
    void overflowFailsBeforeAnyAlterAndCanBeRetriedManually() {
        BaasProject project = createProject("mig-ovf");
        degradeToLegacy(project);
        rootJdbc.update("INSERT INTO `" + project.getDbName() + "`._sessions (id, user_id) "
                + "VALUES (18446744073709551615, 1)");

        migrationService.scanOnce();

        assertThat(reload(project).getStatus()).isEqualTo(ProjectStatus.FAILED);
        assertThat(idColumnType(project.getDbName(), "_users")).isEqualTo("bigint unsigned");
        assertThat(idColumnType(project.getDbName(), "_sessions")).isEqualTo("bigint unsigned");
        assertThat(idColumnType(project.getDbName(), "_refresh_tokens")).isEqualTo("bigint unsigned");
        assertAudit(migrationAudits(project), "SYSTEM_TABLE_MIGRATION_FAILED", 0L,
                "source=BACKGROUND_ACTIVE,result=FAILED,migratedTables=0");

        rootJdbc.update("DELETE FROM `" + project.getDbName() + "`._sessions "
                + "WHERE id = 18446744073709551615");
        SystemTableMigrationResult retried = migrationService.migrate(reload(project));

        assertThat(retried).isEqualTo(new SystemTableMigrationResult("ACTIVE", true));
        assertThat(reload(project).getStatus()).isEqualTo(ProjectStatus.ACTIVE);
    }

    @Test
    void migratingProjectBlocksDdlOperations() {
        BaasProject project = createProject("mig-blk");
        setStatus(project, ProjectStatus.MIGRATING);

        assertThatThrownBy(() -> tableService.createTable(project,
                new TableCreateDTO(UUID.randomUUID().toString(), "blocked", null,
                        List.of(new ColumnDefinitionDTO("a", "int", null, null, true, null, false, false, null)))))
            .isInstanceOf(DdlConflictException.class);
    }

    @Test
    void startupScanResumesMigratingProjectAndSkipsAlreadyMigratedTables() {
        BaasProject project = createProject("mig-res");
        degradeToLegacy(project);
        rootJdbc.execute(SystemTableManifest.legacyMigrationSql(project.getDbName(), "_users"));
        setStatus(project, ProjectStatus.MIGRATING);
        long epochBefore = reload(project).getDdlFenceEpoch();

        migrationService.scanOnce();

        BaasProject reloaded = reload(project);
        assertThat(reloaded.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(reloaded.getDdlFenceEpoch()).isEqualTo(epochBefore + 4L);
        assertThat(idColumnType(project.getDbName(), "_users")).isEqualTo("bigint");
        assertThat(idColumnType(project.getDbName(), "_sessions")).isEqualTo("bigint");
        assertThat(idColumnType(project.getDbName(), "_refresh_tokens")).isEqualTo("bigint");
        assertAudit(migrationAudits(project), "SYSTEM_TABLE_MIGRATION_SUCCESS", 0L,
                "source=BACKGROUND_RESUME,result=SUCCESS,migratedTables=2");
    }

    @Test
    void manualMigrationRejectsMigratingProject() {
        BaasProject project = createProject("mig-man");
        setStatus(project, ProjectStatus.MIGRATING);

        assertThatThrownBy(() -> migrationService.migrate(reload(project)))
            .isInstanceOf(DdlConflictException.class)
            .hasMessageContaining("状态不允许");
    }

    @Test
    void currentManifestRecoversOnlyConfirmedMigrationFailureWithoutAlter() {
        BaasProject project = createProject("mig-rec");
        degradeToLegacy(project);
        rootJdbc.execute(SystemTableManifest.legacyMigrationSql(project.getDbName(), "_users"));
        migrationService.scanOnce();
        rootJdbc.execute(SystemTableManifest.legacyMigrationSql(project.getDbName(), "_sessions"));
        rootJdbc.execute(SystemTableManifest.legacyMigrationSql(project.getDbName(), "_refresh_tokens"));
        long epochBefore = reload(project).getDdlFenceEpoch();

        SystemTableMigrationResult result = migrationService.migrate(reload(project));

        assertThat(result).isEqualTo(new SystemTableMigrationResult("ACTIVE", false));
        assertThat(reload(project).getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(reload(project).getDdlFenceEpoch()).isEqualTo(epochBefore + 1L);
        List<BaasAuditLog> audits = migrationAudits(project);
        assertThat(audits).hasSize(2);
        assertThat(audits.get(0).getAction()).isEqualTo("SYSTEM_TABLE_MIGRATION_FAILED");
        assertThat(audits.get(1).getAction()).isEqualTo("SYSTEM_TABLE_MIGRATION_SUCCESS");
        assertThat(audits.get(1).getDetail())
            .isEqualTo("source=MANUAL,result=SUCCESS,migratedTables=0");
    }

    @Test
    void unknownManifestFailsProjectAndReturnsConflict() {
        BaasProject project = createProject("mig-bad");
        rootJdbc.execute("ALTER TABLE `" + project.getDbName()
                + "`._sessions ALTER COLUMN status SET DEFAULT 'DISABLED'");

        assertThatThrownBy(() -> migrationService.migrate(project))
            .isInstanceOf(DdlConflictException.class)
            .hasMessageContaining("不属于当前版或已知 legacy");
        assertThat(reload(project).getStatus()).isEqualTo(ProjectStatus.FAILED);
        assertAudit(migrationAudits(project), "SYSTEM_TABLE_MIGRATION_FAILED", 1L,
                "source=MANUAL,result=FAILED,migratedTables=0");
    }

    @Test
    void activeMixedManifestFailsWithoutAlterThenConfirmedManualRetryResumes() {
        BaasProject project = createProject("mig-mix");
        degradeToLegacy(project);
        rootJdbc.execute(SystemTableManifest.legacyMigrationSql(project.getDbName(), "_users"));

        migrationService.scanOnce();

        assertThat(reload(project).getStatus()).isEqualTo(ProjectStatus.FAILED);
        assertThat(idColumnType(project.getDbName(), "_users")).isEqualTo("bigint");
        assertThat(idColumnType(project.getDbName(), "_sessions")).isEqualTo("bigint unsigned");
        assertThat(idColumnType(project.getDbName(), "_refresh_tokens")).isEqualTo("bigint unsigned");
        assertAudit(migrationAudits(project), "SYSTEM_TABLE_MIGRATION_FAILED", 0L,
                "source=BACKGROUND_ACTIVE,result=FAILED,migratedTables=0");

        SystemTableMigrationResult result = migrationService.migrate(reload(project));

        assertThat(result).isEqualTo(new SystemTableMigrationResult("ACTIVE", true));
        assertThat(reload(project).getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(idColumnType(project.getDbName(), "_sessions")).isEqualTo("bigint");
        assertThat(idColumnType(project.getDbName(), "_refresh_tokens")).isEqualTo("bigint");
        List<BaasAuditLog> audits = migrationAudits(project);
        assertThat(audits).hasSize(2);
        assertThat(audits.get(1).getDetail())
            .isEqualTo("source=MANUAL,result=SUCCESS,migratedTables=2");
    }

    @Test
    void completedProvisionMarkerWithoutActiveKeysCannotRecoverFailedCurrentProject() {
        BaasProject project = createProject("mig-key");
        apiKeyMapper.update(null, Wrappers.<BaasApiKey>lambdaUpdate()
            .eq(BaasApiKey::getProjectId, project.getId())
            .eq(BaasApiKey::getStatus, "ACTIVE")
            .set(BaasApiKey::getStatus, "REVOKED"));
        setStatus(project, ProjectStatus.FAILED);
        long epochBefore = reload(project).getDdlFenceEpoch();

        assertThatThrownBy(() -> migrationService.migrate(reload(project)))
            .isInstanceOf(DdlConflictException.class)
            .hasMessageContaining("状态不允许");
        assertThat(reload(project).getStatus()).isEqualTo(ProjectStatus.FAILED);
        assertThat(reload(project).getDdlFenceEpoch()).isEqualTo(epochBefore);
        assertThat(reload(project).getProvisionStep()).isEqualTo(ProvisionStep.API_KEYS.name());
        assertThat(migrationAudits(project)).isEmpty();
    }

    @Test
    void incompleteProvisionCannotRecoverFailedCurrentProject() {
        BaasProject project = createProject("mig-step");
        projectMapper.update(null, Wrappers.<BaasProject>lambdaUpdate()
            .eq(BaasProject::getId, project.getId())
            .set(BaasProject::getStatus, ProjectStatus.FAILED)
            .set(BaasProject::getProvisionStep, ProvisionStep.JWT_KEY.name()));

        assertThatThrownBy(() -> migrationService.migrate(reload(project)))
            .isInstanceOf(DdlConflictException.class)
            .hasMessageContaining("状态不允许");
        assertThat(reload(project).getStatus()).isEqualTo(ProjectStatus.FAILED);
        assertThat(migrationAudits(project)).isEmpty();
    }

    @Test
    void staleBackgroundActiveScanDoesNotRetryProjectThatTurnsFailedBeforeLock() {
        BaasProject project = createProject("mig-sta");
        degradeToLegacy(project);
        changeStatusBeforeLock(project, ProjectStatus.FAILED);

        migrationService.scanOnce();

        assertThat(reload(project).getStatus()).isEqualTo(ProjectStatus.FAILED);
        assertThat(idColumnType(project.getDbName(), "_users")).isEqualTo("bigint unsigned");
        assertThat(idColumnType(project.getDbName(), "_sessions")).isEqualTo("bigint unsigned");
        assertThat(idColumnType(project.getDbName(), "_refresh_tokens")).isEqualTo("bigint unsigned");
        assertThat(migrationAudits(project)).isEmpty();
    }

    @Test
    void staleBackgroundResumeDoesNotRunWhenProjectReturnsActiveBeforeLock() {
        BaasProject project = createProject("mig-str");
        degradeToLegacy(project);
        setStatus(project, ProjectStatus.MIGRATING);
        changeStatusBeforeLock(project, ProjectStatus.ACTIVE);

        migrationService.scanOnce();

        assertThat(reload(project).getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(idColumnType(project.getDbName(), "_users")).isEqualTo("bigint unsigned");
        assertThat(idColumnType(project.getDbName(), "_sessions")).isEqualTo("bigint unsigned");
        assertThat(idColumnType(project.getDbName(), "_refresh_tokens")).isEqualTo("bigint unsigned");
        assertThat(migrationAudits(project)).isEmpty();
    }

    @Test
    void backgroundActiveLegacyMigrationUsesSystemActor() {
        BaasProject project = createProject("mig-bga");
        degradeToLegacy(project);

        migrationService.scanOnce();

        assertThat(reload(project).getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        assertAudit(migrationAudits(project), "SYSTEM_TABLE_MIGRATION_SUCCESS", 0L,
                "source=BACKGROUND_ACTIVE,result=SUCCESS,migratedTables=3");
    }

    @Test
    void rejectedSuccessAuditCannotCommitActiveAndCanBeRetriedFromCurrentManifest() {
        BaasProject project = createProject("mig-aus");
        degradeToLegacy(project);
        long epochBefore = reload(project).getDdlFenceEpoch();
        rejectMigrationAudit("SYSTEM_TABLE_MIGRATION_SUCCESS");

        assertThatThrownBy(() -> migrationService.migrate(project)).isInstanceOf(DdlConflictException.class);

        BaasProject failed = reload(project);
        assertThat(failed.getStatus()).isEqualTo(ProjectStatus.FAILED);
        assertThat(failed.getDdlFenceEpoch()).isEqualTo(epochBefore + 5L);
        assertThat(idColumnType(project.getDbName(), "_users")).isEqualTo("bigint");
        assertAudit(migrationAudits(project), "SYSTEM_TABLE_MIGRATION_FAILED", 1L,
                "source=MANUAL,result=FAILED,migratedTables=3");
        rootJdbc.execute("DROP TRIGGER IF EXISTS ai_work_baas.reject_migration_audit");

        SystemTableMigrationResult recovered = migrationService.migrate(failed);

        assertThat(recovered).isEqualTo(new SystemTableMigrationResult("ACTIVE", false));
        assertThat(reload(project).getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(migrationAudits(project)).hasSize(2);
    }

    @Test
    void rejectedFailureAuditRollsBackFailedStatusAndEpoch() {
        BaasProject project = createProject("mig-auf");
        degradeToLegacy(project);
        rootJdbc.update("INSERT INTO `" + project.getDbName() + "`._sessions (id, user_id) "
                + "VALUES (18446744073709551615, 1)");
        long epochBefore = reload(project).getDdlFenceEpoch();
        rejectMigrationAudit("SYSTEM_TABLE_MIGRATION_FAILED");

        assertThatThrownBy(() -> migrationService.migrate(project)).isInstanceOf(DdlConflictException.class);

        assertThat(reload(project).getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(reload(project).getDdlFenceEpoch()).isEqualTo(epochBefore);
        assertThat(migrationAudits(project)).isEmpty();
        assertThat(idColumnType(project.getDbName(), "_users")).isEqualTo("bigint unsigned");
    }

    @Test
    void redisOwnerLossAfterFirstAlterLeavesMigratingAndSuccessorResumesCheckpoint() {
        BaasProject project = createProject("mig-own");
        degradeToLegacy(project);
        String successorToken = "migration-successor-" + project.getId();
        AtomicInteger verifies = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
            Object verified = invocation.callRealMethod();
            if (verifies.incrementAndGet() == 1) {
                redisTemplate.opsForValue().set(DdlLockManager.lockKey(project.getId()), successorToken);
            }
            return verified;
        }).when(fencingGuard).verifyEpochInTx(Mockito.eq(project.getId()), Mockito.anyLong());

        try {
            assertThatThrownBy(() -> migrationService.migrate(project)).isInstanceOf(DdlConflictException.class);

            assertThat(reload(project).getStatus()).isEqualTo(ProjectStatus.MIGRATING);
            assertThat(idColumnType(project.getDbName(), "_users")).isEqualTo("bigint");
            assertThat(idColumnType(project.getDbName(), "_sessions")).isEqualTo("bigint unsigned");
            assertThat(idColumnType(project.getDbName(), "_refresh_tokens")).isEqualTo("bigint unsigned");
            assertThat(redisTemplate.opsForValue().get(DdlLockManager.lockKey(project.getId())))
                .isEqualTo(successorToken);
            assertThat(migrationAudits(project)).isEmpty();
        }
        finally {
            Mockito.reset(fencingGuard);
            redisTemplate.delete(DdlLockManager.lockKey(project.getId()));
        }

        migrationService.scanOnce();

        assertThat(reload(project).getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(idColumnType(project.getDbName(), "_sessions")).isEqualTo("bigint");
        assertThat(idColumnType(project.getDbName(), "_refresh_tokens")).isEqualTo("bigint");
        assertAudit(migrationAudits(project), "SYSTEM_TABLE_MIGRATION_SUCCESS", 0L,
                "source=BACKGROUND_RESUME,result=SUCCESS,migratedTables=2");
    }

    @Test
    void successorEpochAdvancePreventsStaleExecutorFromWritingTerminalState() {
        BaasProject project = createProject("mig-epo");
        degradeToLegacy(project);
        AtomicInteger verifies = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
            if (verifies.incrementAndGet() == 4) {
                rootJdbc.update("UPDATE baas_project SET ddl_fence_epoch = ddl_fence_epoch + 1 WHERE id = ?",
                        project.getId());
            }
            return invocation.callRealMethod();
        }).when(fencingGuard).verifyEpochInTx(Mockito.eq(project.getId()), Mockito.anyLong());

        assertThatThrownBy(() -> migrationService.migrate(project)).isInstanceOf(DdlConflictException.class);

        assertThat(reload(project).getStatus()).isEqualTo(ProjectStatus.MIGRATING);
        assertThat(idColumnType(project.getDbName(), "_users")).isEqualTo("bigint");
        assertThat(idColumnType(project.getDbName(), "_sessions")).isEqualTo("bigint");
        assertThat(idColumnType(project.getDbName(), "_refresh_tokens")).isEqualTo("bigint");
        assertThat(migrationAudits(project)).isEmpty();
    }

    @Test
    void lockBusyReturnsConflictInsteadOfFalseSuccess() {
        BaasProject project = createProject("mig-busy");
        LockHandle held = lockManager.tryAcquire(project.getId());
        assertThat(held).isNotNull();
        try {
            assertThatThrownBy(() -> migrationService.migrate(project))
                .isInstanceOf(DdlConflictException.class)
                .hasMessageContaining("DDL 操作进行中");
        }
        finally {
            lockManager.release(held);
        }
        assertThat(reload(project).getStatus()).isEqualTo(ProjectStatus.ACTIVE);
    }

    private BaasProject createProject(String namePrefix) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return lifecycleService.createProject(namePrefix + "-" + suffix, 1L).project();
    }

    private BaasProject reload(BaasProject project) {
        return projectMapper.selectById(project.getId());
    }

    private void setStatus(BaasProject project, ProjectStatus status) {
        projectMapper.update(null, Wrappers.<BaasProject>lambdaUpdate()
            .eq(BaasProject::getId, project.getId())
            .set(BaasProject::getStatus, status));
    }

    @SuppressWarnings("unchecked")
    private void changeStatusBeforeLock(BaasProject project, ProjectStatus status) {
        Mockito.doAnswer(invocation -> {
            ProjectDdlLockCallback<Object> callback = invocation.getArgument(1);
            ProjectDdlLockCallback<Object> wrapped = (handle, connection) -> {
                setStatus(project, status);
                return callback.doInLock(handle, connection);
            };
            invocation.getRawArguments()[1] = wrapped;
            return invocation.callRealMethod();
        }).when(lockExecutor).execute(Mockito.eq(project.getId()), Mockito.any());
    }

    private void rejectMigrationAudit(String action) {
        rootJdbc.execute("DROP TRIGGER IF EXISTS ai_work_baas.reject_migration_audit");
        rootJdbc.execute("CREATE TRIGGER ai_work_baas.reject_migration_audit "
                + "BEFORE INSERT ON ai_work_baas.baas_audit_log FOR EACH ROW "
                + "BEGIN IF NEW.action = '" + action + "' THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'migration audit unavailable'; "
                + "END IF; END");
    }

    private List<BaasAuditLog> migrationAudits(BaasProject project) {
        return auditLogMapper.selectList(Wrappers.<BaasAuditLog>lambdaQuery()
            .eq(BaasAuditLog::getProjectId, project.getId())
            .in(BaasAuditLog::getAction,
                    "SYSTEM_TABLE_MIGRATION_SUCCESS", "SYSTEM_TABLE_MIGRATION_FAILED")
            .orderByAsc(BaasAuditLog::getId));
    }

    private void assertAudit(List<BaasAuditLog> audits, String action, Long operatorUserId, String detail) {
        assertThat(audits).hasSize(1);
        BaasAuditLog audit = audits.get(0);
        assertThat(audit.getAction()).isEqualTo(action);
        assertThat(audit.getOperatorUserId()).isEqualTo(operatorUserId);
        assertThat(audit.getDetail()).isEqualTo(detail);
        assertThat(audit.getDetail()).doesNotContain("ALTER", "jdbc:", "password", "credential");
    }

    private void degradeToLegacy(BaasProject project) {
        for (String tableName : SystemTableManifest.SYSTEM_TABLE_NAMES) {
            rootJdbc.execute("DROP TABLE `" + project.getDbName() + "`.`" + tableName + "`");
        }
        LegacySystemTables.create(rootJdbc, project.getDbName());
    }

    private String idColumnType(String dbName, String tableName) {
        return rootJdbc.queryForObject("SELECT COLUMN_TYPE FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND COLUMN_NAME = 'id'", String.class, dbName,
                tableName);
    }

}
