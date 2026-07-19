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

import com.aiwork.baas.ddl.engine.DdlFencingGuard;
import com.aiwork.baas.ddl.inspect.PhysicalTable;
import com.aiwork.baas.ddl.inspect.SchemaInspector;
import com.aiwork.baas.ddl.lock.DdlLockBusyException;
import com.aiwork.baas.ddl.lock.LockHandle;
import com.aiwork.baas.ddl.lock.ProjectDdlLockExecutor;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.enums.ProjectStatus;
import com.aiwork.baas.exception.DdlConflictException;
import com.aiwork.baas.mapper.BaasProjectMapper;
import com.aiwork.baas.provision.IdentifierValidator;
import com.aiwork.baas.provision.PhysicalPreconditions;
import com.aiwork.baas.provision.ProvisionerDataSourceHolder;
import com.aiwork.baas.provision.SystemTableManifest;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 存量项目系统表迁移(spec §9.1)：ACTIVE → MIGRATING → ACTIVE/FAILED，
 * 双层锁下逐表检查点执行，参与项目级 epoch，越界预检先于一切 ALTER。
 *
 * @author ai-work
 * @date 2026/07/19
 */
@Slf4j
@Service
public class SystemTableMigrationService {

    private final BaasProjectMapper projectMapper;

    private final ProjectDdlLockExecutor lockExecutor;

    private final DdlFencingGuard fencingGuard;

    private final PhysicalPreconditions physicalPreconditions;

    private final TransactionTemplate transactionTemplate;

    private final JdbcTemplate provisionerJdbc;

    private final boolean startupScanEnabled;

    public SystemTableMigrationService(BaasProjectMapper projectMapper, ProjectDdlLockExecutor lockExecutor,
            DdlFencingGuard fencingGuard, PhysicalPreconditions physicalPreconditions,
            TransactionTemplate transactionTemplate, ProvisionerDataSourceHolder holder,
            @Value("${baas.migration.startup-scan-enabled:true}") boolean startupScanEnabled) {
        this.projectMapper = projectMapper;
        this.lockExecutor = lockExecutor;
        this.fencingGuard = fencingGuard;
        this.physicalPreconditions = physicalPreconditions;
        this.transactionTemplate = transactionTemplate;
        this.provisionerJdbc = new JdbcTemplate(holder.dataSource());
        this.startupScanEnabled = startupScanEnabled;
    }

    @Scheduled(initialDelayString = "${baas.migration.scan-initial-delay-millis:10000}",
            fixedDelayString = "${baas.migration.scan-interval-millis:60000}")
    public void scheduledScan() {
        if (!startupScanEnabled) {
            return;
        }
        physicalPreconditions.refresh();
        if (!physicalPreconditions.isSatisfied()) {
            log.error("system table scan disabled: BAAS_PHYSICAL_PRECONDITIONS_FAILED");
            return;
        }
        scanOnce();
    }

    /**
     * 后台扫描：ACTIVE 先按 manifest 快速筛选，MIGRATING 按物理检查点恢复。
     */
    public void scanOnce() {
        physicalPreconditions.assertSatisfied();
        for (BaasProject project : projectMapper.selectList(Wrappers.<BaasProject>lambdaQuery()
            .in(BaasProject::getStatus, ProjectStatus.ACTIVE, ProjectStatus.MIGRATING))) {
            try {
                if (project.getStatus() == ProjectStatus.MIGRATING) {
                    resumeMigration(project);
                }
                else if (SystemTableManifest.compare(readSystemTables(project.getDbName()))
                        != SystemTableManifest.MatchResult.MATCH_CURRENT) {
                    migrate(project);
                }
            }
            catch (RuntimeException failure) {
                log.warn("system table migration scan failed projectId={} errorType={}", project.getId(),
                        failure.getClass().getSimpleName());
            }
        }
    }

    /**
     * 管理员手动迁移入口，仅接受 ACTIVE/FAILED；状态在双层锁内重新确认。
     * @param project 目标项目
     * @return 本轮同步迁移结果
     */
    public SystemTableMigrationResult migrate(BaasProject project) {
        if (project == null || project.getId() == null) {
            throw new DdlConflictException("项目当前状态不允许系统表迁移");
        }
        AtomicReference<SystemTableMigrationResult> committedResult = new AtomicReference<>();
        try {
            physicalPreconditions.assertSatisfied();
            return lockExecutor.execute(project.getId(), (handle, connection) -> {
                SystemTableMigrationResult result = doMigrateInLock(project, handle, connection, false);
                committedResult.set(result);
                return result;
            });
        }
        catch (DdlLockBusyException busy) {
            if (committedResult.get() != null) {
                return committedResult.get();
            }
            throw new DdlConflictException("该项目有 DDL 操作进行中");
        }
        catch (DdlConflictException conflict) {
            throw conflict;
        }
        catch (RuntimeException failure) {
            log.warn("system table migration failed projectId={} errorType={}", project.getId(),
                    failure.getClass().getSimpleName());
            throw new DdlConflictException("系统表迁移失败");
        }
    }

    private void resumeMigration(BaasProject project) {
        try {
            lockExecutor.execute(project.getId(),
                    (handle, connection) -> doMigrateInLock(project, handle, connection, true));
        }
        catch (RuntimeException failure) {
            log.warn("system table migration resume failed projectId={} errorType={}", project.getId(),
                    failure.getClass().getSimpleName());
        }
    }

    private SystemTableMigrationResult doMigrateInLock(BaasProject project, LockHandle handle,
            Connection connection, boolean resume) {
        BaasProject current = projectMapper.selectById(project.getId());
        boolean manualState = current != null && (current.getStatus() == ProjectStatus.ACTIVE
                || current.getStatus() == ProjectStatus.FAILED);
        boolean resumeState = current != null && resume && current.getStatus() == ProjectStatus.MIGRATING;
        if (!manualState && !resumeState) {
            throw new DdlConflictException("项目当前状态不允许系统表迁移");
        }

        IdentifierValidator.validate(current.getDbName());
        JdbcTemplate lockedJdbc = SchemaInspector.jdbcFor(connection);
        Map<String, PhysicalTable> tables = preflight(current, handle, lockedJdbc);
        SystemTableManifest.MatchResult manifest = SystemTableManifest.compare(tables);
        if (manifest == SystemTableManifest.MatchResult.MATCH_CURRENT) {
            return recoverCurrentProject(current, handle);
        }

        lockExecutor.assertStillHeld(handle);
        long epoch = transitionWithEpoch(current.getId(), current.getStatus(), ProjectStatus.MIGRATING);
        try {
            for (String tableName : SystemTableManifest.SYSTEM_TABLE_NAMES) {
                PhysicalTable table = SchemaInspector.readTable(lockedJdbc, current.getDbName(), tableName);
                if (SystemTableManifest.tableMatches(tableName, table, false)) {
                    continue;
                }
                lockedJdbc.execute(SystemTableManifest.legacyMigrationSql(current.getDbName(), tableName));
                PhysicalTable migrated = SchemaInspector.readTable(lockedJdbc, current.getDbName(), tableName);
                if (!SystemTableManifest.tableMatches(tableName, migrated, false)) {
                    throw new DdlConflictException("系统表迁移失败");
                }
                lockExecutor.assertStillHeld(handle);
                epoch = advanceEpochCheckpoint(current.getId(), epoch);
            }
            lockExecutor.assertStillHeld(handle);
            transitionVerified(current.getId(), epoch, ProjectStatus.MIGRATING, ProjectStatus.ACTIVE);
            return new SystemTableMigrationResult(ProjectStatus.ACTIVE.name(), true);
        }
        catch (RuntimeException failure) {
            markFailedVerified(current.getId(), epoch, handle);
            if (failure instanceof DdlConflictException conflict) {
                throw conflict;
            }
            throw new DdlConflictException("系统表迁移失败");
        }
    }

    private Map<String, PhysicalTable> preflight(BaasProject current, LockHandle handle, JdbcTemplate lockedJdbc) {
        try {
            Map<String, PhysicalTable> tables = readSystemTables(lockedJdbc, current.getDbName());
            SystemTableManifest.MatchResult manifest = SystemTableManifest.compare(tables);
            if (manifest == SystemTableManifest.MatchResult.MISMATCH) {
                markFailedFromPreflight(current, handle);
                throw new DdlConflictException("系统表结构不属于当前版或已知 legacy 版本");
            }
            if (manifest == SystemTableManifest.MatchResult.MATCH_LEGACY_PLAN_A) {
                assertNoOverflow(current, handle, lockedJdbc, tables);
            }
            return tables;
        }
        catch (DdlConflictException conflict) {
            throw conflict;
        }
        catch (DdlLockBusyException lost) {
            throw lost;
        }
        catch (RuntimeException failure) {
            markFailedFromPreflight(current, handle);
            log.warn("system table migration preflight failed projectId={} errorType={}", current.getId(),
                    failure.getClass().getSimpleName());
            throw new DdlConflictException("系统表迁移失败");
        }
    }

    private void assertNoOverflow(BaasProject current, LockHandle handle, JdbcTemplate lockedJdbc,
            Map<String, PhysicalTable> tables) {
        for (String tableName : SystemTableManifest.SYSTEM_TABLE_NAMES) {
            if (SystemTableManifest.tableMatches(tableName, tables.get(tableName), false)) {
                continue;
            }
            Long overflow = lockedJdbc.queryForObject(
                    SystemTableManifest.unsignedBoundsCheckSql(current.getDbName(), tableName), Long.class);
            if (overflow != null && overflow > 0) {
                markFailedFromPreflight(current, handle);
                throw new DdlConflictException("系统表存在超出 signed bigint 范围的数据");
            }
        }
    }

    private SystemTableMigrationResult recoverCurrentProject(BaasProject current, LockHandle handle) {
        if (current.getStatus() != ProjectStatus.ACTIVE) {
            lockExecutor.assertStillHeld(handle);
            transitionWithEpoch(current.getId(), current.getStatus(), ProjectStatus.ACTIVE);
        }
        return new SystemTableMigrationResult(ProjectStatus.ACTIVE.name(), false);
    }

    private void markFailedFromPreflight(BaasProject current, LockHandle handle) {
        if (current.getStatus() != ProjectStatus.FAILED) {
            lockExecutor.assertStillHeld(handle);
            transitionWithEpoch(current.getId(), current.getStatus(), ProjectStatus.FAILED);
        }
    }

    private void markFailedVerified(Long projectId, long heldEpoch, LockHandle handle) {
        lockExecutor.assertStillHeld(handle);
        transactionTemplate.executeWithoutResult(status -> {
            fencingGuard.verifyEpochInTx(projectId, heldEpoch);
            fencingGuard.incrementEpochInTx(projectId);
            int updated = projectMapper.update(null, Wrappers.<BaasProject>lambdaUpdate()
                .eq(BaasProject::getId, projectId)
                .eq(BaasProject::getStatus, ProjectStatus.MIGRATING)
                .set(BaasProject::getStatus, ProjectStatus.FAILED));
            if (updated != 1) {
                throw new IllegalStateException("project migration failure transition race");
            }
        });
    }

    private long transitionWithEpoch(Long projectId, ProjectStatus from, ProjectStatus to) {
        return transactionTemplate.execute(status -> {
            long newEpoch = fencingGuard.incrementEpochInTx(projectId);
            int updated = projectMapper.update(null, Wrappers.<BaasProject>lambdaUpdate()
                .eq(BaasProject::getId, projectId)
                .eq(BaasProject::getStatus, from)
                .set(BaasProject::getStatus, to));
            if (updated != 1) {
                throw new IllegalStateException("project status transition race: " + from + " -> " + to);
            }
            return newEpoch;
        });
    }

    private long advanceEpochCheckpoint(Long projectId, long heldEpoch) {
        return transactionTemplate.execute(status -> {
            fencingGuard.verifyEpochInTx(projectId, heldEpoch);
            return fencingGuard.incrementEpochInTx(projectId);
        });
    }

    private void transitionVerified(Long projectId, long heldEpoch, ProjectStatus from, ProjectStatus to) {
        transactionTemplate.executeWithoutResult(status -> {
            fencingGuard.verifyEpochInTx(projectId, heldEpoch);
            fencingGuard.incrementEpochInTx(projectId);
            int updated = projectMapper.update(null, Wrappers.<BaasProject>lambdaUpdate()
                .eq(BaasProject::getId, projectId)
                .eq(BaasProject::getStatus, from)
                .set(BaasProject::getStatus, to));
            if (updated != 1) {
                throw new IllegalStateException("project status transition race: " + from + " -> " + to);
            }
        });
    }

    private Map<String, PhysicalTable> readSystemTables(String dbName) {
        IdentifierValidator.validate(dbName);
        return readSystemTables(provisionerJdbc, dbName);
    }

    private Map<String, PhysicalTable> readSystemTables(JdbcTemplate jdbcTemplate, String dbName) {
        Map<String, PhysicalTable> tables = new HashMap<>();
        for (String tableName : SystemTableManifest.SYSTEM_TABLE_NAMES) {
            tables.put(tableName, SchemaInspector.readTable(jdbcTemplate, dbName, tableName));
        }
        return tables;
    }

}
