/*
 *
 *      Copyright (c) 2018-2025, lengleng All rights reserved.
 *
 */

package com.aiwork.baas.service;

import com.aiwork.baas.controller.dto.ColumnDefinitionDTO;
import com.aiwork.baas.controller.dto.TableCreateDTO;
import com.aiwork.baas.ddl.RequestFingerprint;
import com.aiwork.baas.ddl.lock.DdlLockManager;
import com.aiwork.baas.ddl.lock.LockHandle;
import com.aiwork.baas.ddl.lock.ProjectDdlLockExecutor;
import com.aiwork.baas.ddl.render.DdlRenderer;
import com.aiwork.baas.entity.BaasDdlLog;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.BaasTable;
import com.aiwork.baas.entity.enums.DdlLogStatus;
import com.aiwork.baas.entity.enums.DdlOperationType;
import com.aiwork.baas.entity.enums.DdlStep;
import com.aiwork.baas.entity.enums.TableStatus;
import com.aiwork.baas.mapper.BaasDdlLogMapper;
import com.aiwork.baas.mapper.BaasProjectMapper;
import com.aiwork.baas.mapper.BaasTableMapper;
import com.aiwork.baas.support.PlanBProjectIntegrationTestSupport;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DDL 维护调度器真实 MySQL/Redis 集成测试。
 *
 * @author ai-work
 * @date 2026/07/18
 */
class DdlMaintenanceJobTest extends PlanBProjectIntegrationTestSupport {

    @Autowired
    private DdlMaintenanceJob maintenanceJob;

    @Autowired
    private TableManagementService tableService;

    @Autowired
    private BaasTableMapper tableMapper;

    @Autowired
    private BaasDdlLogMapper ddlLogMapper;

    @Autowired
    private BaasProjectMapper projectMapper;

    @Autowired
    private DdlLockManager lockManager;

    @MockitoSpyBean
    private ProjectDdlLockExecutor lockExecutor;

    @Override
    protected String projectNamePrefix() {
        return "maint";
    }

    @Test
    void duePendingCleanupDropsTableAndReleasesName() {
        String tableName = createAndDrop("mt_due");
        Long tableId = tableRow(tableName).getId();
        makeCleanupDue(tableName);

        maintenanceJob.scanOnce();

        assertThat(physicalTableCount(tableName)).isZero();
        assertThat(tableMapper.selectById(tableId)).isNull();
        tableService.createTable(project, createDto(tableName));
    }

    @Test
    void notDuePendingStaysUnclaimed() {
        String tableName = createAndDrop("mt_notdue");

        maintenanceJob.scanOnce();

        BaasDdlLog cleanup = cleanupLog(tableName);
        assertThat(cleanup.getStatus()).isEqualTo(DdlLogStatus.PENDING.name());
        assertThat(cleanup.getOwnerToken()).isNull();
    }

    @Test
    void staleCleanupNeverDropsRecreatedSameNameTable() {
        String tableName = "mt_ghost";
        tableService.createTable(project, createDto(tableName));
        BaasDdlLog ghost = insertCleanupRecord(tableName, 999999L, DdlLogStatus.PENDING, DdlStep.PREPARED,
                null, null);

        maintenanceJob.scanOnce();

        assertThat(physicalTableCount(tableName)).isEqualTo(1L);
        BaasDdlLog reloaded = ddlLogMapper.selectById(ghost.getId());
        assertThat(reloaded.getStatus()).isEqualTo(DdlLogStatus.SUCCESS.name());
        assertThat(reloaded.getResultSnapshot()).contains("noop");
    }

    @Test
    void pendingCleanupPointingAtActiveRowNoopsWithoutDrop() {
        String tableName = "mt_active";
        tableService.createTable(project, createDto(tableName));
        BaasTable active = tableRow(tableName);
        BaasDdlLog cleanup = insertCleanupRecord(tableName, active.getId(), DdlLogStatus.PENDING,
                DdlStep.PREPARED, null, null);

        maintenanceJob.scanOnce();

        assertThat(physicalTableCount(tableName)).isEqualTo(1L);
        assertThat(tableMapper.selectById(active.getId()).getStatus()).isEqualTo(TableStatus.ACTIVE.name());
        assertThat(ddlLogMapper.selectById(cleanup.getId()).getStatus()).isEqualTo(DdlLogStatus.SUCCESS.name());
    }

    @Test
    void failedCleanupRetriedAndRunningWithDeadLeaseTakenOver() {
        String failedTable = createAndDrop("mt_failed");
        makeCleanupDue(failedTable);
        BaasDdlLog failed = cleanupLog(failedTable);
        updateCleanupOwnership(failed, DdlLogStatus.FAILED, DdlStep.PREPARED, "dead-failed", 1L);

        String runningTable = createAndDrop("mt_running");
        makeCleanupDue(runningTable);
        BaasDdlLog running = cleanupLog(runningTable);
        updateCleanupOwnership(running, DdlLogStatus.RUNNING, DdlStep.PREPARED, "dead-running", 1L);

        maintenanceJob.scanOnce();

        assertThat(ddlLogMapper.selectById(failed.getId()).getStatus()).isEqualTo(DdlLogStatus.SUCCESS.name());
        assertThat(ddlLogMapper.selectById(running.getId()).getStatus()).isEqualTo(DdlLogStatus.SUCCESS.name());
        assertThat(ddlLogMapper.selectById(failed.getId()).getRetryCount()).isEqualTo(1);
    }

    @Test
    void cleanupWithLiveLeaseIsNotTakenOver() {
        String tableName = createAndDrop("mt_live");
        makeCleanupDue(tableName);
        BaasDdlLog cleanup = cleanupLog(tableName);
        LockHandle owner = lockManager.tryAcquire(project.getId());
        try {
            updateCleanupOwnership(cleanup, DdlLogStatus.RUNNING, DdlStep.PREPARED, owner.ownerToken(), 1L);

            maintenanceJob.scanOnce();

            assertThat(ddlLogMapper.selectById(cleanup.getId()).getStatus())
                .isEqualTo(DdlLogStatus.RUNNING.name());
            assertThat(physicalTableCount(tableName)).isEqualTo(1L);
        }
        finally {
            lockManager.release(owner);
        }
    }

    @Test
    void concurrentScansClaimPendingCleanupOnce() throws Exception {
        String tableName = createAndDrop("mt_concurrent");
        makeCleanupDue(tableName);
        long epochBefore = projectMapper.selectById(project.getId()).getDdlFenceEpoch();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<?>> futures = new ArrayList<>(2);
        try {
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("maintenance start timeout");
                    }
                    maintenanceJob.scanOnce();
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }
        finally {
            executor.shutdownNow();
        }

        BaasDdlLog cleanup = cleanupLog(tableName);
        assertThat(cleanup.getStatus()).isEqualTo(DdlLogStatus.SUCCESS.name());
        assertThat(cleanup.getRetryCount()).isZero();
        assertThat(projectMapper.selectById(project.getId()).getDdlFenceEpoch()).isEqualTo(epochBefore + 1);
        assertThat(physicalTableCount(tableName)).isZero();
    }

    @Test
    void ddlAppliedCleanupResumesMetadataAfterCrash() {
        String tableName = createAndDrop("mt_crash");
        Long tableId = tableRow(tableName).getId();
        makeCleanupDue(tableName);
        BaasDdlLog cleanup = cleanupLog(tableName);
        rootJdbc.execute(DdlRenderer.renderDropTable(project.getDbName(), tableName).sql());
        updateCleanupOwnership(cleanup, DdlLogStatus.FAILED, DdlStep.DDL_APPLIED, "dead-after-ddl", 1L);

        maintenanceJob.scanOnce();

        assertThat(ddlLogMapper.selectById(cleanup.getId()).getStatus()).isEqualTo(DdlLogStatus.SUCCESS.name());
        assertThat(ddlLogMapper.selectById(cleanup.getId()).getStep()).isEqualTo(DdlStep.METADATA_APPLIED.name());
        assertThat(tableMapper.selectById(tableId)).isNull();
    }

    @Test
    void successfulCleanupIsNeverReexecutedAgainstRecreatedName() {
        String tableName = createAndDrop("mt_success");
        makeCleanupDue(tableName);
        maintenanceJob.scanOnce();
        BaasDdlLog cleanup = cleanupLog(tableName);
        tableService.createTable(project, createDto(tableName));

        maintenanceJob.scanOnce();

        assertThat(ddlLogMapper.selectById(cleanup.getId()).getStatus()).isEqualTo(DdlLogStatus.SUCCESS.name());
        assertThat(physicalTableCount(tableName)).isEqualTo(1L);
        assertThat(tableRow(tableName).getStatus()).isEqualTo(TableStatus.ACTIVE.name());
    }

    @Test
    void staleHttpRunningForcedFailedAndExactRetryUsesPhysicalProbe() {
        TableCreateDTO dto = createDto("mt_http_probe");
        DdlRenderer.RenderedDdl rendered = renderCreate(dto);
        BaasTable creating = insertTable(dto.tableName(), TableStatus.CREATING);
        rootJdbc.execute(rendered.sql());
        BaasDdlLog stale = insertHttpLog(dto.operationId(), DdlOperationType.CREATE, dto.tableName(),
                creating.getId(), requestHash(dto), DdlStep.DDL_APPLIED, rendered.sanitizedSql());
        makeLogStale(stale.getId());

        maintenanceJob.scanOnce();

        assertThat(ddlLogMapper.selectById(stale.getId()).getStatus()).isEqualTo(DdlLogStatus.FAILED.name());
        assertThat(tableMapper.selectById(creating.getId()).getStatus()).isEqualTo(TableStatus.FAILED.name());

        ObjectNode snapshot = tableService.createTable(project, dto);

        assertThat(snapshot.get("status").asText()).isEqualTo(TableStatus.ACTIVE.name());
        assertThat(ddlLogMapper.selectById(stale.getId()).getStatus()).isEqualTo(DdlLogStatus.SUCCESS.name());
        assertThat(physicalTableCount(dto.tableName())).isEqualTo(1L);
    }

    @Test
    void staleHttpFallbackAppliesOnlySpecifiedTableStateTransitions() {
        BaasTable creating = insertTable("mt_stale_c", TableStatus.CREATING);
        BaasDdlLog create = insertHttpLog(UUID.randomUUID().toString(), DdlOperationType.CREATE,
                creating.getTableName(), creating.getId(), "c".repeat(64), DdlStep.PREPARED, null);
        BaasTable altering = insertTable("mt_stale_a", TableStatus.ALTERING);
        BaasDdlLog alter = insertHttpLog(UUID.randomUUID().toString(), DdlOperationType.ALTER,
                altering.getTableName(), altering.getId(), "a".repeat(64), DdlStep.DDL_APPLIED, null);
        BaasTable aclApplied = insertTable("mt_stale_acl", TableStatus.ALTERING);
        BaasDdlLog acl = insertHttpLog(UUID.randomUUID().toString(), DdlOperationType.ACL_CONFIG,
                aclApplied.getTableName(), aclApplied.getId(), "l".repeat(64), DdlStep.DDL_APPLIED,
                "ALTER TABLE `db`.`mt_stale_acl` ADD INDEX `idx_owner_id` (`owner_id`)");
        BaasTable aclPreparedWithDdl = insertTable("mt_stale_acl_d", TableStatus.ALTERING);
        BaasDdlLog aclWithPreparedDdl = insertHttpLog(UUID.randomUUID().toString(), DdlOperationType.ACL_CONFIG,
                aclPreparedWithDdl.getTableName(), aclPreparedWithDdl.getId(), "q".repeat(64), DdlStep.PREPARED,
                "ALTER TABLE `db`.`mt_stale_acl_d` ADD INDEX `idx_owner_id` (`owner_id`)");
        BaasTable aclPrepared = insertTable("mt_stale_acl_p", TableStatus.ACTIVE);
        BaasDdlLog aclWithoutDdl = insertHttpLog(UUID.randomUUID().toString(), DdlOperationType.ACL_CONFIG,
                aclPrepared.getTableName(), aclPrepared.getId(), "p".repeat(64), DdlStep.PREPARED, null);
        BaasTable dropping = insertTable("mt_stale_d", TableStatus.DELETED);
        BaasDdlLog drop = insertHttpLog(UUID.randomUUID().toString(), DdlOperationType.DROP,
                dropping.getTableName(), dropping.getId(), "d".repeat(64), DdlStep.PREPARED, null);
        List.of(create, alter, acl, aclWithPreparedDdl, aclWithoutDdl, drop)
            .forEach(log -> makeLogStale(log.getId()));

        maintenanceJob.scanOnce();

        assertThat(List.of(create, alter, acl, aclWithPreparedDdl, aclWithoutDdl, drop))
            .allSatisfy(log -> assertThat(ddlLogMapper.selectById(log.getId()).getStatus())
                .isEqualTo(DdlLogStatus.FAILED.name()));
        assertThat(tableMapper.selectById(creating.getId()).getStatus()).isEqualTo(TableStatus.FAILED.name());
        assertThat(tableMapper.selectById(altering.getId()).getStatus()).isEqualTo(TableStatus.CONFLICT.name());
        assertThat(tableMapper.selectById(aclApplied.getId()).getStatus()).isEqualTo(TableStatus.CONFLICT.name());
        assertThat(tableMapper.selectById(aclPreparedWithDdl.getId()).getStatus())
            .isEqualTo(TableStatus.CONFLICT.name());
        assertThat(tableMapper.selectById(aclPrepared.getId()).getStatus()).isEqualTo(TableStatus.ACTIVE.name());
        assertThat(tableMapper.selectById(dropping.getId()).getStatus()).isEqualTo(TableStatus.DELETED.name());
    }

    @Test
    void staleHttpWithLiveLeaseAndFreshRunningAreNotTouched() {
        BaasDdlLog fresh = insertHttpLog(UUID.randomUUID().toString(), DdlOperationType.ALTER, "mt_fresh",
                null, "f".repeat(64), DdlStep.PREPARED, null);
        BaasDdlLog live = insertHttpLog(UUID.randomUUID().toString(), DdlOperationType.ALTER, "mt_live_http",
                null, "v".repeat(64), DdlStep.PREPARED, null);
        makeLogStale(live.getId());
        LockHandle owner = lockManager.tryAcquire(project.getId());
        try {
            metaJdbc().update("UPDATE baas_ddl_log SET owner_token = ? WHERE id = ?", owner.ownerToken(),
                    live.getId());

            maintenanceJob.scanOnce();

            assertThat(ddlLogMapper.selectById(fresh.getId()).getStatus())
                .isEqualTo(DdlLogStatus.RUNNING.name());
            assertThat(ddlLogMapper.selectById(live.getId()).getStatus())
                .isEqualTo(DdlLogStatus.RUNNING.name());
        }
        finally {
            lockManager.release(owner);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void staleThresholdIsRecheckedAfterTakingProjectLock() {
        BaasDdlLog stale = insertHttpLog(UUID.randomUUID().toString(), DdlOperationType.DROP, "mt_refreshed",
                null, "r".repeat(64), DdlStep.PREPARED, null);
        makeLogStale(stale.getId());
        AtomicBoolean refreshed = new AtomicBoolean();
        Mockito.doAnswer(invocation -> {
            if (refreshed.compareAndSet(false, true)) {
                metaJdbc().update("UPDATE baas_ddl_log SET update_time = ? WHERE id = ?",
                        LocalDateTime.now().plusMinutes(1), stale.getId());
            }
            return invocation.callRealMethod();
        })
            .when(lockExecutor)
            .execute(Mockito.eq(project.getId()), Mockito.any());
        try {
            maintenanceJob.scanOnce();
        }
        finally {
            Mockito.reset(lockExecutor);
        }

        assertThat(refreshed).isTrue();
        assertThat(ddlLogMapper.selectById(stale.getId()).getStatus()).isEqualTo(DdlLogStatus.RUNNING.name());
    }

    private JdbcTemplate metaJdbc() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL_USERNAME,
                MYSQL_PASSWORD);
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        return new JdbcTemplate(dataSource);
    }

    private TableCreateDTO createDto(String tableName) {
        return new TableCreateDTO(UUID.randomUUID().toString(), tableName, null,
                List.of(new ColumnDefinitionDTO("name", "varchar", 64, null, true, null, false, false, null)));
    }

    private String createAndDrop(String tableName) {
        tableService.createTable(project, createDto(tableName));
        tableService.dropTable(project, tableName, UUID.randomUUID().toString());
        return tableName;
    }

    private void makeCleanupDue(String tableName) {
        metaJdbc().update("UPDATE baas_table SET delete_after = DATE_SUB(NOW(), INTERVAL 1 DAY) "
                + "WHERE project_id = ? AND table_name = ?", project.getId(), tableName);
    }

    private void makeLogStale(Long logId) {
        metaJdbc().update("UPDATE baas_ddl_log SET update_time = DATE_SUB(NOW(), INTERVAL 1 HOUR) WHERE id = ?",
                logId);
    }

    private void updateCleanupOwnership(BaasDdlLog cleanup, DdlLogStatus status, DdlStep step, String ownerToken,
            Long fenceEpoch) {
        metaJdbc().update("UPDATE baas_ddl_log SET status = ?, step = ?, owner_token = ?, fence_epoch = ? "
                + "WHERE id = ?", status.name(), step.name(), ownerToken, fenceEpoch, cleanup.getId());
    }

    private BaasDdlLog insertCleanupRecord(String tableName, Long tableId, DdlLogStatus status, DdlStep step,
            String ownerToken, Long fenceEpoch) {
        BaasDdlLog cleanup = new BaasDdlLog();
        cleanup.setProjectId(project.getId());
        cleanup.setOperationId(UUID.randomUUID().toString());
        cleanup.setOperationType(DdlOperationType.CLEANUP_DROP.code());
        cleanup.setTableName(tableName);
        cleanup.setTableId(tableId);
        cleanup.setRequestHash("g".repeat(64));
        cleanup.setStep(step.name());
        cleanup.setStatus(status.name());
        cleanup.setOwnerToken(ownerToken);
        cleanup.setFenceEpoch(fenceEpoch);
        cleanup.setRetryCount(0);
        ddlLogMapper.insert(cleanup);
        return cleanup;
    }

    private BaasDdlLog insertHttpLog(String operationId, DdlOperationType type, String tableName, Long tableId,
            String requestHash, DdlStep step, String ddlText) {
        BaasDdlLog log = new BaasDdlLog();
        log.setProjectId(project.getId());
        log.setOperationId(operationId);
        log.setOperationType(type.code());
        log.setTableName(tableName);
        log.setTableId(tableId);
        log.setRequestHash(requestHash);
        log.setOwnerToken("dead-http-" + UUID.randomUUID());
        log.setFenceEpoch(projectMapper.selectById(project.getId()).getDdlFenceEpoch());
        log.setDdlText(ddlText);
        log.setStep(step.name());
        log.setStatus(DdlLogStatus.RUNNING.name());
        log.setRetryCount(0);
        ddlLogMapper.insert(log);
        return log;
    }

    private BaasTable insertTable(String tableName, TableStatus status) {
        BaasTable table = new BaasTable();
        table.setProjectId(project.getId());
        table.setTableName(tableName);
        table.setStatus(status.name());
        tableMapper.insert(table);
        return table;
    }

    private DdlRenderer.RenderedDdl renderCreate(TableCreateDTO dto) {
        return DdlRenderer.renderCreateTable(project.getDbName(), dto.tableName(), dto.comment(), dto.columns()
            .stream()
            .map(TableManagementService::toColumnPlan)
            .toList());
    }

    private String requestHash(TableCreateDTO dto) {
        return RequestFingerprint.http("POST", "/studio/projects/" + project.getProjectRef() + "/tables",
                DdlOperationType.CREATE.code(), RequestFingerprint.canonicalBody(dto));
    }

    private BaasTable tableRow(String tableName) {
        return tableMapper.selectOne(Wrappers.<BaasTable>lambdaQuery()
            .eq(BaasTable::getProjectId, project.getId())
            .eq(BaasTable::getTableName, tableName));
    }

    private BaasDdlLog cleanupLog(String tableName) {
        return ddlLogMapper.selectOne(Wrappers.<BaasDdlLog>lambdaQuery()
            .eq(BaasDdlLog::getProjectId, project.getId())
            .eq(BaasDdlLog::getTableName, tableName)
            .eq(BaasDdlLog::getOperationType, DdlOperationType.CLEANUP_DROP.code()));
    }

    private Long physicalTableCount(String tableName) {
        return rootJdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?", Long.class, project.getDbName(), tableName);
    }

}
