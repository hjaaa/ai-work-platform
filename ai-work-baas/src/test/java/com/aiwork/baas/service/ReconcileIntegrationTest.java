/*
 *
 *      Copyright (c) 2018-2026, lengleng All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice,
 *  this list of conditions and the following disclaimer.
 *  Redistributions in binary form must reproduce the above copyright
 *  notice, this list of conditions and the following disclaimer in the
 *  documentation and/or other materials provided with the distribution.
 *  Neither the name of the pig4cloud.com developer nor the names of its
 *  contributors may be used to endorse or promote products derived from
 *  this software without specific prior written permission.
 *  Author: lengleng (wangiegie@gmail.com)
 *
 */

package com.aiwork.baas.service;

import com.aiwork.baas.controller.dto.AclConfigDTO;
import com.aiwork.baas.controller.dto.AclPutDTO;
import com.aiwork.baas.controller.dto.AclRoleDTO;
import com.aiwork.baas.controller.dto.ColumnDefinitionDTO;
import com.aiwork.baas.controller.dto.ReconcileTriggerDTO;
import com.aiwork.baas.controller.dto.TableCreateDTO;
import com.aiwork.baas.ddl.RequestFingerprint;
import com.aiwork.baas.ddl.engine.DdlExecutionEngine;
import com.aiwork.baas.ddl.engine.DdlOperationSpec;
import com.aiwork.baas.ddl.engine.DdlWork;
import com.aiwork.baas.ddl.engine.DdlWorkContext;
import com.aiwork.baas.ddl.lock.DdlLockManager;
import com.aiwork.baas.ddl.lock.LockHandle;
import com.aiwork.baas.entity.BaasAuditLog;
import com.aiwork.baas.entity.BaasDdlLog;
import com.aiwork.baas.entity.BaasTable;
import com.aiwork.baas.entity.enums.DdlLogStatus;
import com.aiwork.baas.entity.enums.DdlOperationType;
import com.aiwork.baas.entity.enums.DdlStep;
import com.aiwork.baas.entity.enums.TableStatus;
import com.aiwork.baas.exception.BaasBadRequestException;
import com.aiwork.baas.exception.DdlConflictException;
import com.aiwork.baas.exception.DdlExecutionException;
import com.aiwork.baas.mapper.BaasAuditLogMapper;
import com.aiwork.baas.mapper.BaasDdlLogMapper;
import com.aiwork.baas.mapper.BaasProjectMapper;
import com.aiwork.baas.mapper.BaasTableMapper;
import com.aiwork.baas.provision.PhysicalPreconditions;
import com.aiwork.baas.security.TestCurrentUserProvider;
import com.aiwork.baas.support.PlanBProjectIntegrationTestSupport;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;

import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 项目库表结构对账集成测试。
 *
 * @author ai-work
 * @date 2026/07/19
 */
class ReconcileIntegrationTest extends PlanBProjectIntegrationTestSupport {

    @Override
    protected String projectNamePrefix() {
        return "recon";
    }

    @Autowired
    private ReconcileService reconcileService;

    @Autowired
    private TableManagementService tableService;

    @Autowired
    private AclConfigService aclService;

    @Autowired
    private BaasTableMapper tableMapper;

    @Autowired
    private BaasDdlLogMapper ddlLogMapper;

    @Autowired
    private BaasAuditLogMapper auditLogMapper;

    @Autowired
    private BaasProjectMapper projectMapper;

    @Autowired
    private DdlExecutionEngine engine;

    @Autowired
    private DdlLockManager lockManager;

    @Autowired
    private PhysicalPreconditions physicalPreconditions;

    private ObjectNode reconcile() {
        return reconcileService.manualReconcile(project, new ReconcileTriggerDTO(UUID.randomUUID().toString()));
    }

    private String db() {
        return project.getDbName();
    }

    private String createManaged(String name) {
        tableService.createTable(project, new TableCreateDTO(UUID.randomUUID().toString(), name, null, List.of(
                new ColumnDefinitionDTO("name", "varchar", 64, null, true, null, false, false, null))));
        return name;
    }

    private String statusOf(String name) {
        return tableMapper.selectOne(Wrappers.<BaasTable>lambdaQuery()
            .eq(BaasTable::getProjectId, project.getId())
            .eq(BaasTable::getTableName, name)).getStatus();
    }

    @Test
    void missingPhysicalTableMarksConflict() {
        String table = createManaged("rc_missing");
        rootJdbc.execute("DROP TABLE `" + db() + "`.`" + table + "`");

        ObjectNode report = reconcile();

        assertThat(statusOf(table)).isEqualTo(TableStatus.CONFLICT.name());
        assertThat(report.get("conflicts").toString()).contains(table);
    }

    @Test
    void admissibleExternalTableImportedWithClosedAcl() {
        rootJdbc.execute("CREATE TABLE `" + db() + "`.rc_import (id bigint NOT NULL AUTO_INCREMENT, "
                + "email varchar(255) NOT NULL, PRIMARY KEY (id), UNIQUE KEY foo_email (email)) "
                + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC "
                + "COMMENT='外部表注释'");

        ObjectNode report = reconcile();

        assertThat(report.get("imported").toString()).contains("rc_import");
        ObjectNode snapshot = tableService.getTableSnapshot(project, "rc_import");
        assertThat(snapshot.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(snapshot.get("comment").asText()).isEqualTo("外部表注释");
        assertThat(snapshot.at("/acl/anon/select").asBoolean()).isFalse();
        assertThat(snapshot.get("columns").toString()).contains("\"unique\":true");
    }

    @Test
    void inadmissibleExternalTablesRejectedWithReasons() {
        rootJdbc.execute("CREATE TABLE `" + db() + "`.rc_myisam (id bigint NOT NULL AUTO_INCREMENT, "
                + "PRIMARY KEY (id)) ENGINE=MyISAM DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
        rootJdbc.execute("CREATE VIEW `" + db() + "`.rc_view AS SELECT 1 AS x");
        rootJdbc.execute("CREATE TABLE `" + db() + "`.rc_check (id bigint NOT NULL AUTO_INCREMENT, "
                + "n int CHECK (n > 0), PRIMARY KEY (id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 "
                + "COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC");
        rootJdbc.execute("CREATE TABLE `" + db() + "`.rc_unsigned (id bigint NOT NULL AUTO_INCREMENT, "
                + "n int unsigned, PRIMARY KEY (id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 "
                + "COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC");
        rootJdbc.execute("CREATE TABLE `" + db() + "`.rc_prefix (id bigint NOT NULL AUTO_INCREMENT, "
                + "name varchar(300), PRIMARY KEY (id), KEY idx_p (name(10))) ENGINE=InnoDB "
                + "DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC");
        rootJdbc.execute("CREATE TABLE `" + db() + "`.rc_onupd (id bigint NOT NULL AUTO_INCREMENT, "
                + "touched datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, PRIMARY KEY (id)) "
                + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC");

        ObjectNode report = reconcile();

        String rejected = report.get("rejectedImports").toString();
        for (String name : List.of("rc_myisam", "rc_view", "rc_check", "rc_unsigned", "rc_prefix", "rc_onupd")) {
            assertThat(rejected).contains(name);
            assertThat(tableMapper.selectCount(Wrappers.<BaasTable>lambdaQuery()
                .eq(BaasTable::getProjectId, project.getId())
                .eq(BaasTable::getTableName, name))).isZero();
        }
    }

    @Test
    void physicalDefaultsOutsideLogicalModelRejectImportAndConflictManagedTables() {
        rootJdbc.execute("CREATE TABLE `" + db() + "`.rc_default_bool_import "
                + "(id bigint NOT NULL AUTO_INCREMENT, vip tinyint(1) DEFAULT 2, PRIMARY KEY (id)) "
                + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC");
        executeWithoutStrictMode("CREATE TABLE `" + db() + "`.rc_default_dt_import "
                + "(id bigint NOT NULL AUTO_INCREMENT, created datetime DEFAULT '0000-00-00 00:00:00', "
                + "PRIMARY KEY (id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 "
                + "COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC");

        String boolManaged = "rc_default_bool_managed";
        tableService.createTable(project, new TableCreateDTO(UUID.randomUUID().toString(), boolManaged, null,
                List.of(new ColumnDefinitionDTO("vip", "boolean", null, null, true,
                        MAPPER.getNodeFactory().booleanNode(true), false, false, null))));
        rootJdbc.execute("ALTER TABLE `" + db() + "`.`" + boolManaged + "` "
                + "MODIFY vip tinyint(1) NULL DEFAULT 2");

        String datetimeManaged = "rc_default_dt_managed";
        tableService.createTable(project, new TableCreateDTO(UUID.randomUUID().toString(), datetimeManaged, null,
                List.of(new ColumnDefinitionDTO("created", "datetime", null, null, true,
                        MAPPER.getNodeFactory().textNode("CURRENT_TIMESTAMP"), false, false, null))));
        executeWithoutStrictMode("ALTER TABLE `" + db() + "`.`" + datetimeManaged + "` "
                + "MODIFY created datetime NULL DEFAULT '0000-00-00 00:00:00'");

        ObjectNode report = reconcile();

        String rejected = report.get("rejectedImports").toString();
        assertThat(rejected).contains("rc_default_bool_import").contains("rc_default_dt_import");
        for (String external : List.of("rc_default_bool_import", "rc_default_dt_import")) {
            assertThat(tableMapper.selectCount(Wrappers.<BaasTable>lambdaQuery()
                .eq(BaasTable::getProjectId, project.getId())
                .eq(BaasTable::getTableName, external))).isZero();
        }
        assertThat(report.get("conflicts").toString()).contains(boolManaged).contains(datetimeManaged);
        assertThat(statusOf(boolManaged)).isEqualTo(TableStatus.CONFLICT.name());
        assertThat(statusOf(datetimeManaged)).isEqualTo(TableStatus.CONFLICT.name());
    }

    @Test
    void mappableDriftCorrectedFromDatabase() {
        String table = createManaged("rc_drift");
        rootJdbc.execute("ALTER TABLE `" + db() + "`.`" + table + "` ADD COLUMN extra_col int NULL, "
                + "MODIFY name varchar(128) NULL COMMENT 'changed', COMMENT='库侧新注释'");

        ObjectNode report = reconcile();

        assertThat(report.get("corrected").toString()).contains(table);
        ObjectNode snapshot = tableService.getTableSnapshot(project, table);
        assertThat(snapshot.get("columns").toString()).contains("extra_col").contains("\"length\":128");
        assertThat(snapshot.get("comment").asText()).isEqualTo("库侧新注释");
    }

    @Test
    void whitelistViolationMarksConflict() {
        String table = createManaged("rc_float");
        rootJdbc.execute("ALTER TABLE `" + db() + "`.`" + table + "` ADD COLUMN f float");

        reconcile();

        assertThat(statusOf(table)).isEqualTo(TableStatus.CONFLICT.name());
    }

    @Test
    void compositeIndexMarksConflictNotCompressed() {
        String table = createManaged("rc_comp");
        rootJdbc.execute("ALTER TABLE `" + db() + "`.`" + table + "` ADD COLUMN a int, ADD COLUMN b int, "
                + "ADD INDEX idx_ab (a, b)");

        reconcile();

        assertThat(statusOf(table)).isEqualTo(TableStatus.CONFLICT.name());
    }

    @Test
    void ownerConstraintBreakMarksConflictWithoutCorrection() {
        String table = "rc_owner";
        tableService.createTable(project, new TableCreateDTO(UUID.randomUUID().toString(), table, null, List.of(
                new ColumnDefinitionDTO("owner_id", "bigint", null, null, true, null, false, false, null))));
        aclService.putAcl(project, table, new AclPutDTO(UUID.randomUUID().toString(),
                new AclConfigDTO(new AclRoleDTO(false, false, false, false),
                        new AclRoleDTO(true, false, false, false)),
                "owner_id"));
        rootJdbc.execute("ALTER TABLE `" + db() + "`.`" + table + "` DROP INDEX idx_owner_id");

        reconcile();
        assertThat(statusOf(table)).isEqualTo(TableStatus.CONFLICT.name());

        rootJdbc.execute("ALTER TABLE `" + db() + "`.`" + table + "` ADD INDEX idx_owner_id (owner_id)");
        reconcile();
        assertThat(statusOf(table)).isEqualTo(TableStatus.ACTIVE.name());
    }

    @Test
    void tombstoneNeverResurrected() {
        String table = createManaged("rc_tomb");
        tableService.dropTable(project, table, UUID.randomUUID().toString());

        reconcile();

        assertThat(statusOf(table)).isEqualTo(TableStatus.DELETED.name());
    }

    @Test
    void noDriftForBooleanAndCurrentTimestampDefaults() {
        String table = "rc_stable";
        tableService.createTable(project, new TableCreateDTO(UUID.randomUUID().toString(), table, null, List.of(
                new ColumnDefinitionDTO("vip", "boolean", null, null, true,
                        MAPPER.getNodeFactory().booleanNode(true), false, false, null),
                new ColumnDefinitionDTO("created", "datetime", null, null, true,
                        MAPPER.getNodeFactory().textNode("CURRENT_TIMESTAMP"), false, false, null))));

        ObjectNode report = reconcile();

        assertThat(report.get("corrected").toString()).doesNotContain(table);
        assertThat(statusOf(table)).isEqualTo(TableStatus.ACTIVE.name());
    }

    @Test
    void manualAuditUsesCurrentUserAndSuccessReplayDoesNotDuplicate() {
        String table = createManaged("rc_replay");
        rootJdbc.execute("ALTER TABLE `" + db() + "`.`" + table + "` ADD COLUMN extra_col int NULL");
        ReconcileTriggerDTO dto = new ReconcileTriggerDTO(UUID.randomUUID().toString());
        Long previousUserId = TestCurrentUserProvider.userId;
        ObjectNode first;
        ObjectNode second;
        try {
            TestCurrentUserProvider.userId = 73L;
            first = reconcileService.manualReconcile(project, dto);
            second = reconcileService.manualReconcile(project, dto);
        }
        finally {
            TestCurrentUserProvider.userId = previousUserId;
        }

        assertThat(second).isEqualTo(first);
        assertThat(first.get("corrected").toString()).contains(table);
        List<BaasAuditLog> audits = reconcileAudits();
        assertThat(audits).hasSize(1);
        BaasAuditLog audit = audits.get(0);
        assertThat(audit.getOperatorUserId()).isEqualTo(73L);
        assertThat(audit.getLevel()).isEqualTo("INFO");
        assertThat(audit.getDetail()).isEqualTo("operationId=" + dto.operationId()
                + ",trigger=MANUAL,corrected=1,imported=0,recovered=0,conflicts=0,rejectedImports=0");
    }

    @Test
    void scheduledAuditUsesExplicitSystemActor() {
        ObjectNode report = reconcileService.scheduledReconcile(project);

        BaasDdlLog ddlLog = scheduledLogs().get(0);
        assertThat(ddlLog.getResultSnapshot()).isEqualTo(report.toString());
        List<BaasAuditLog> audits = reconcileAudits();
        assertThat(audits).hasSize(1);
        BaasAuditLog audit = audits.get(0);
        assertThat(audit.getOperatorUserId()).isZero();
        assertThat(audit.getLevel()).isEqualTo("INFO");
        assertThat(audit.getDetail()).isEqualTo("operationId=" + ddlLog.getOperationId()
                + ",trigger=SCHEDULED,corrected=0,imported=0,recovered=0,conflicts=0,rejectedImports=0");
    }

    @Test
    void auditFailureRollsBackMetadataAndSuccessThenSameOperationRetries() {
        String table = createManaged("rc_audit_rollback");
        rootJdbc.execute("ALTER TABLE `" + db() + "`.`" + table + "` ADD COLUMN extra_col int NULL");
        ReconcileTriggerDTO dto = new ReconcileTriggerDTO(UUID.randomUUID().toString());
        rootJdbc.execute("DROP TRIGGER IF EXISTS ai_work_baas.reject_reconcile_audit");
        rootJdbc.execute("CREATE TRIGGER ai_work_baas.reject_reconcile_audit "
                + "BEFORE INSERT ON ai_work_baas.baas_audit_log FOR EACH ROW "
                + "BEGIN IF NEW.action = 'DDL_RECONCILE' THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'reconcile audit unavailable'; "
                + "END IF; END");
        try {
            assertThatThrownBy(() -> reconcileService.manualReconcile(project, dto))
                .isInstanceOf(DdlExecutionException.class);

            assertThat(tableService.getTableSnapshot(project, table).get("columns").toString())
                .doesNotContain("extra_col");
            assertThat(reconcileAudits()).isEmpty();
            BaasDdlLog failed = ddlLogMapper.selectByProjectAndOperation(project.getId(), dto.operationId());
            assertThat(failed.getStatus()).isEqualTo(DdlLogStatus.FAILED.name());
            assertThat(failed.getResultSnapshot()).isNull();
        }
        finally {
            rootJdbc.execute("DROP TRIGGER IF EXISTS ai_work_baas.reject_reconcile_audit");
        }

        ObjectNode retried = reconcileService.manualReconcile(project, dto);

        assertThat(retried.get("corrected").toString()).contains(table);
        assertThat(tableService.getTableSnapshot(project, table).get("columns").toString())
            .contains("extra_col");
        assertThat(reconcileAudits()).hasSize(1);
        BaasDdlLog succeeded = ddlLogMapper.selectByProjectAndOperation(project.getId(), dto.operationId());
        assertThat(succeeded.getStatus()).isEqualTo(DdlLogStatus.SUCCESS.name());
        assertThat(succeeded.getResultSnapshot()).isEqualTo(retried.toString());
    }

    @Test
    void malformedManualOperationIdRejectedBeforeLogCreation() {
        ReconcileTriggerDTO dto = new ReconcileTriggerDTO("not-a-uuid");

        assertThatThrownBy(() -> reconcileService.manualReconcile(project, dto))
            .isInstanceOf(BaasBadRequestException.class)
            .hasMessageContaining("UUID");
    }

    @Test
    void schedulerAlreadyInRoundTakesOverRunningLeftByCrashedPeer() throws Exception {
        BaasProjectMapper scanMapper = mock(BaasProjectMapper.class);
        when(scanMapper.selectList(any())).thenReturn(List.of(project));
        CountDownLatch secondAtServiceBoundary = new CountDownLatch(1);
        CountDownLatch allowSecondToResolve = new CountDownLatch(1);

        ReconcileService delayedSecondService = spy(reconcileService);
        doAnswer(invocation -> {
            secondAtServiceBoundary.countDown();
            if (!allowSecondToResolve.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("second scheduler wait timed out");
            }
            return reconcileService.scheduledReconcile(invocation.getArgument(0));
        }).when(delayedSecondService).scheduledReconcile(any());
        ScheduledReconcileJob secondJob = new ScheduledReconcileJob(physicalPreconditions, delayedSecondService,
                scanMapper);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> secondRound = executor.submit(secondJob::scanOnce);
            assertThat(secondAtServiceBoundary.await(10, TimeUnit.SECONDS)).isTrue();

            String operationId = UUID.randomUUID().toString();
            ReconcileService crashingFirstService = spy(reconcileService);
            doAnswer(invocation -> engine.execute(scheduledSpec(operationId), new DdlWork() {
                @Override
                public void validateInLock(DdlWorkContext context) {
                }

                @Override
                public ObjectNode perform(DdlWorkContext context) {
                    throw new SimulatedProcessCrash();
                }
            })).when(crashingFirstService).scheduledReconcile(any());
            ScheduledReconcileJob firstJob = new ScheduledReconcileJob(physicalPreconditions, crashingFirstService,
                    scanMapper);

            assertThatThrownBy(firstJob::scanOnce).isInstanceOf(SimulatedProcessCrash.class);
            assertThat(ddlLogMapper.selectByProjectAndOperation(project.getId(), operationId).getStatus())
                .isEqualTo(DdlLogStatus.RUNNING.name());

            allowSecondToResolve.countDown();
            secondRound.get(20, TimeUnit.SECONDS);

            List<BaasDdlLog> scheduledLogs = ddlLogMapper.selectList(Wrappers.<BaasDdlLog>lambdaQuery()
                .eq(BaasDdlLog::getProjectId, project.getId())
                .eq(BaasDdlLog::getOperationType, DdlOperationType.RECONCILE.code())
                .eq(BaasDdlLog::getTriggerSource, ReconcileService.TRIGGER_SCHEDULED));
            assertThat(scheduledLogs).hasSize(1);
            assertThat(scheduledLogs.get(0).getOperationId()).isEqualTo(operationId);
            assertThat(scheduledLogs.get(0).getStatus()).isEqualTo(DdlLogStatus.SUCCESS.name());
        }
        finally {
            allowSecondToResolve.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void scheduledFailedReusesPersistedOperationIdAndHash() {
        String operationId = UUID.randomUUID().toString();
        String requestHash = RequestFingerprint.scheduledReconcile(project.getId(), operationId);
        seedScheduledLog(operationId, requestHash, DdlLogStatus.FAILED, "failed-owner");

        reconcileService.scheduledReconcile(project);

        List<BaasDdlLog> scheduledLogs = scheduledLogs();
        assertThat(scheduledLogs).hasSize(1);
        assertThat(scheduledLogs.get(0).getOperationId()).isEqualTo(operationId);
        assertThat(scheduledLogs.get(0).getRequestHash()).isEqualTo(requestHash);
        assertThat(scheduledLogs.get(0).getStatus()).isEqualTo(DdlLogStatus.SUCCESS.name());
        assertThat(scheduledLogs.get(0).getRetryCount()).isEqualTo(1);
    }

    @Test
    void scheduledActiveRunningSkipsWithoutCreatingAnotherOperation() {
        LockHandle activeOwner = lockManager.tryAcquire(project.getId());
        assertThat(activeOwner).isNotNull();
        String operationId = UUID.randomUUID().toString();
        String requestHash = RequestFingerprint.scheduledReconcile(project.getId(), operationId);
        seedScheduledLog(operationId, requestHash, DdlLogStatus.RUNNING, activeOwner.ownerToken());
        try {
            assertThatThrownBy(() -> reconcileService.scheduledReconcile(project))
                .isInstanceOf(DdlConflictException.class);
            List<BaasDdlLog> scheduledLogs = scheduledLogs();
            assertThat(scheduledLogs).hasSize(1);
            assertThat(scheduledLogs.get(0).getOperationId()).isEqualTo(operationId);
            assertThat(scheduledLogs.get(0).getStatus()).isEqualTo(DdlLogStatus.RUNNING.name());
        }
        finally {
            lockManager.release(activeOwner);
        }
    }

    private DdlOperationSpec scheduledSpec(String operationId) {
        return new DdlOperationSpec(project.getId(), operationId, DdlOperationType.RECONCILE, null, null,
                RequestFingerprint.scheduledReconcile(project.getId(), operationId),
                ReconcileService.TRIGGER_SCHEDULED, null);
    }

    private void seedScheduledLog(String operationId, String requestHash, DdlLogStatus status, String ownerToken) {
        BaasDdlLog log = new BaasDdlLog();
        log.setProjectId(project.getId());
        log.setOperationId(operationId);
        log.setOperationType(DdlOperationType.RECONCILE.code());
        log.setRequestHash(requestHash);
        log.setTriggerSource(ReconcileService.TRIGGER_SCHEDULED);
        log.setStep(DdlStep.PREPARED.name());
        log.setStatus(status.name());
        log.setOwnerToken(ownerToken);
        log.setFenceEpoch(0L);
        log.setRetryCount(0);
        ddlLogMapper.insert(log);
    }

    private List<BaasDdlLog> scheduledLogs() {
        return ddlLogMapper.selectList(Wrappers.<BaasDdlLog>lambdaQuery()
            .eq(BaasDdlLog::getProjectId, project.getId())
            .eq(BaasDdlLog::getOperationType, DdlOperationType.RECONCILE.code())
            .eq(BaasDdlLog::getTriggerSource, ReconcileService.TRIGGER_SCHEDULED));
    }

    private List<BaasAuditLog> reconcileAudits() {
        return auditLogMapper.selectList(Wrappers.<BaasAuditLog>lambdaQuery()
            .eq(BaasAuditLog::getProjectId, project.getId())
            .eq(BaasAuditLog::getAction, "DDL_RECONCILE")
            .orderByAsc(BaasAuditLog::getId));
    }

    private void executeWithoutStrictMode(String sql) {
        rootJdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET SESSION sql_mode = ''");
                statement.execute(sql);
            }
            finally {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET SESSION sql_mode = DEFAULT");
                }
            }
            return null;
        });
    }

    private static final class SimulatedProcessCrash extends Error {
    }

}
