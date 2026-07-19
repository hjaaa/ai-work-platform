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
import com.aiwork.baas.ddl.lock.DdlLockManager;
import com.aiwork.baas.ddl.lock.LockHandle;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.enums.ProjectStatus;
import com.aiwork.baas.exception.DdlConflictException;
import com.aiwork.baas.mapper.BaasProjectMapper;
import com.aiwork.baas.provision.LegacySystemTables;
import com.aiwork.baas.provision.SystemTableManifest;
import com.aiwork.baas.support.PlanBContainerSupport;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.UUID;

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

    @Test
    void currentProjectReturnsFalseWithoutEpochChange() {
        BaasProject project = createProject("mig-cur");
        long epochBefore = reload(project).getDdlFenceEpoch();

        SystemTableMigrationResult result = migrationService.migrate(project);

        assertThat(result).isEqualTo(new SystemTableMigrationResult("ACTIVE", false));
        assertThat(reload(project).getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(reload(project).getDdlFenceEpoch()).isEqualTo(epochBefore);
    }

    @Test
    void legacyProjectMigratedToSignedWithEpochParticipation() {
        BaasProject project = createProject("mig-leg");
        degradeToLegacy(project);
        rootJdbc.update("INSERT INTO `" + project.getDbName() + "`._users (email, password_hash) "
                + "VALUES ('a@b.c', 'h')");
        long epochBefore = reload(project).getDdlFenceEpoch();

        SystemTableMigrationResult result = migrationService.migrate(project);

        assertThat(result).isEqualTo(new SystemTableMigrationResult("ACTIVE", true));
        BaasProject reloaded = reload(project);
        assertThat(reloaded.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(reloaded.getDdlFenceEpoch()).isEqualTo(epochBefore + 5L);
        assertThat(idColumnType(project.getDbName(), "_users")).isEqualTo("bigint");
        assertThat(idColumnType(project.getDbName(), "_sessions")).isEqualTo("bigint");
        assertThat(idColumnType(project.getDbName(), "_refresh_tokens")).isEqualTo("bigint");
        assertThat(rootJdbc.queryForObject("SELECT COUNT(*) FROM `" + project.getDbName() + "`._users",
                Long.class)).isEqualTo(1L);
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
    void currentManifestRecoversFailedProjectWithoutAlter() {
        BaasProject project = createProject("mig-rec");
        setStatus(project, ProjectStatus.FAILED);
        long epochBefore = reload(project).getDdlFenceEpoch();

        SystemTableMigrationResult result = migrationService.migrate(reload(project));

        assertThat(result).isEqualTo(new SystemTableMigrationResult("ACTIVE", false));
        assertThat(reload(project).getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(reload(project).getDdlFenceEpoch()).isEqualTo(epochBefore + 1L);
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
