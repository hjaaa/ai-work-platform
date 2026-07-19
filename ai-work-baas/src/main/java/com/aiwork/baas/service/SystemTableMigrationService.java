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
import com.aiwork.baas.entity.BaasApiKey;
import com.aiwork.baas.entity.BaasAuditLog;
import com.aiwork.baas.entity.BaasJwtKey;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.enums.JwtKeyStatus;
import com.aiwork.baas.entity.enums.KeyType;
import com.aiwork.baas.entity.enums.ProjectStatus;
import com.aiwork.baas.entity.enums.ProvisionStep;
import com.aiwork.baas.exception.DdlConflictException;
import com.aiwork.baas.mapper.BaasApiKeyMapper;
import com.aiwork.baas.mapper.BaasAuditLogMapper;
import com.aiwork.baas.mapper.BaasJwtKeyMapper;
import com.aiwork.baas.mapper.BaasProjectMapper;
import com.aiwork.baas.provision.IdentifierValidator;
import com.aiwork.baas.provision.PhysicalPreconditions;
import com.aiwork.baas.provision.ProvisionerDataSourceHolder;
import com.aiwork.baas.provision.SystemTableManifest;
import com.aiwork.baas.security.CurrentUserProvider;
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

    private static final Long SYSTEM_OPERATOR_USER_ID = 0L;

    private static final String AUDIT_SUCCESS = "SYSTEM_TABLE_MIGRATION_SUCCESS";

    private static final String AUDIT_FAILED = "SYSTEM_TABLE_MIGRATION_FAILED";

    private enum MigrationSource {

        MANUAL, BACKGROUND_ACTIVE, BACKGROUND_RESUME

    }

    private final BaasProjectMapper projectMapper;

    private final BaasApiKeyMapper apiKeyMapper;

    private final BaasJwtKeyMapper jwtKeyMapper;

    private final BaasAuditLogMapper auditLogMapper;

    private final ProjectDdlLockExecutor lockExecutor;

    private final DdlFencingGuard fencingGuard;

    private final PhysicalPreconditions physicalPreconditions;

    private final TransactionTemplate transactionTemplate;

    private final CurrentUserProvider userProvider;

    private final JdbcTemplate provisionerJdbc;

    private final boolean startupScanEnabled;

    public SystemTableMigrationService(BaasProjectMapper projectMapper, BaasApiKeyMapper apiKeyMapper,
            BaasJwtKeyMapper jwtKeyMapper, BaasAuditLogMapper auditLogMapper,
            ProjectDdlLockExecutor lockExecutor, DdlFencingGuard fencingGuard,
            PhysicalPreconditions physicalPreconditions, TransactionTemplate transactionTemplate,
            CurrentUserProvider userProvider, ProvisionerDataSourceHolder holder,
            @Value("${baas.migration.startup-scan-enabled:true}") boolean startupScanEnabled) {
        this.projectMapper = projectMapper;
        this.apiKeyMapper = apiKeyMapper;
        this.jwtKeyMapper = jwtKeyMapper;
        this.auditLogMapper = auditLogMapper;
        this.lockExecutor = lockExecutor;
        this.fencingGuard = fencingGuard;
        this.physicalPreconditions = physicalPreconditions;
        this.transactionTemplate = transactionTemplate;
        this.userProvider = userProvider;
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
                    executeMigration(project, MigrationSource.BACKGROUND_RESUME, SYSTEM_OPERATOR_USER_ID);
                }
                else if (SystemTableManifest.compare(readSystemTables(project.getDbName()))
                        != SystemTableManifest.MatchResult.MATCH_CURRENT) {
                    executeMigration(project, MigrationSource.BACKGROUND_ACTIVE, SYSTEM_OPERATOR_USER_ID);
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
        return executeMigration(project, MigrationSource.MANUAL, userProvider.currentUserId());
    }

    private SystemTableMigrationResult executeMigration(BaasProject project, MigrationSource source,
            Long operatorUserId) {
        AtomicReference<SystemTableMigrationResult> committedResult = new AtomicReference<>();
        try {
            physicalPreconditions.assertSatisfied();
            return lockExecutor.execute(project.getId(), (handle, connection) -> {
                SystemTableMigrationResult result = doMigrateInLock(project, handle, connection, source,
                        operatorUserId);
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

    private SystemTableMigrationResult doMigrateInLock(BaasProject project, LockHandle handle,
            Connection connection, MigrationSource source, Long operatorUserId) {
        BaasProject current = projectMapper.selectById(project.getId());
        if (!isSourceAllowed(current, source)) {
            throw new DdlConflictException("项目当前状态不允许系统表迁移");
        }

        IdentifierValidator.validate(current.getDbName());
        JdbcTemplate lockedJdbc = SchemaInspector.jdbcFor(connection);
        Map<String, PhysicalTable> tables = readPreflightTables(current, handle, lockedJdbc, source,
                operatorUserId);
        SystemTableManifest.MatchResult manifest = SystemTableManifest.compare(tables);
        if (manifest == SystemTableManifest.MatchResult.MISMATCH
                || (current.getStatus() == ProjectStatus.ACTIVE
                        && manifest == SystemTableManifest.MatchResult.MATCH_MIXED)) {
            recordPreflightFailure(current, handle, source, operatorUserId);
            throw new DdlConflictException("系统表结构不属于当前版或已知 legacy 版本");
        }
        if (manifest == SystemTableManifest.MatchResult.MATCH_CURRENT) {
            return recoverCurrentProject(current, handle, source, operatorUserId);
        }
        boolean overflow;
        try {
            overflow = hasUnsignedOverflow(current, lockedJdbc, tables);
        }
        catch (RuntimeException failure) {
            recordPreflightFailure(current, handle, source, operatorUserId);
            log.warn("system table migration preflight failed projectId={} errorType={}", current.getId(),
                    failure.getClass().getSimpleName());
            throw new DdlConflictException("系统表迁移失败");
        }
        if (overflow) {
            recordPreflightFailure(current, handle, source, operatorUserId);
            throw new DdlConflictException("系统表存在超出 signed bigint 范围的数据");
        }

        lockExecutor.assertStillHeld(handle);
        long epoch = transitionWithEpoch(current.getId(), current.getStatus(), ProjectStatus.MIGRATING);
        int migratedTableCount = 0;
        try {
            for (String tableName : SystemTableManifest.SYSTEM_TABLE_NAMES) {
                lockExecutor.assertStillHeld(handle);
                PhysicalTable table = SchemaInspector.readTable(lockedJdbc, current.getDbName(), tableName);
                if (SystemTableManifest.tableMatches(tableName, table, false)) {
                    continue;
                }
                lockExecutor.assertStillHeld(handle);
                lockedJdbc.execute(SystemTableManifest.legacyMigrationSql(current.getDbName(), tableName));
                PhysicalTable migrated = SchemaInspector.readTable(lockedJdbc, current.getDbName(), tableName);
                if (!SystemTableManifest.tableMatches(tableName, migrated, false)) {
                    throw new DdlConflictException("系统表迁移失败");
                }
                lockExecutor.assertStillHeld(handle);
                epoch = advanceEpochCheckpoint(current.getId(), epoch);
                migratedTableCount++;
            }
            lockExecutor.assertStillHeld(handle);
            transitionVerifiedWithAudit(current.getId(), epoch, ProjectStatus.MIGRATING,
                    ProjectStatus.ACTIVE, source, operatorUserId, migratedTableCount);
            return new SystemTableMigrationResult(ProjectStatus.ACTIVE.name(), true);
        }
        catch (RuntimeException failure) {
            markFailedVerified(current.getId(), epoch, handle, source, operatorUserId, migratedTableCount);
            if (failure instanceof DdlConflictException conflict) {
                throw conflict;
            }
            throw new DdlConflictException("系统表迁移失败");
        }
    }

    private boolean isSourceAllowed(BaasProject current, MigrationSource source) {
        if (current == null) {
            return false;
        }
        return switch (source) {
            case MANUAL -> current.getStatus() == ProjectStatus.ACTIVE
                    || (current.getStatus() == ProjectStatus.FAILED && isConfirmedMigrationFailure(current));
            case BACKGROUND_ACTIVE -> current.getStatus() == ProjectStatus.ACTIVE;
            case BACKGROUND_RESUME -> current.getStatus() == ProjectStatus.MIGRATING;
        };
    }

    private boolean isConfirmedMigrationFailure(BaasProject current) {
        if (!ProvisionStep.API_KEYS.name().equals(current.getProvisionStep())
                || activeKeyCount(current.getId(), KeyType.PUBLISHABLE) == 0
                || activeKeyCount(current.getId(), KeyType.SECRET) == 0
                || jwtKeyMapper.selectCount(Wrappers.<BaasJwtKey>lambdaQuery()
                    .eq(BaasJwtKey::getProjectId, current.getId())
                    .eq(BaasJwtKey::getStatus, JwtKeyStatus.CURRENT)) == 0) {
            return false;
        }
        BaasAuditLog latestMigrationAudit = auditLogMapper.selectOne(Wrappers.<BaasAuditLog>lambdaQuery()
            .eq(BaasAuditLog::getProjectId, current.getId())
            .in(BaasAuditLog::getAction, AUDIT_SUCCESS, AUDIT_FAILED)
            .orderByDesc(BaasAuditLog::getId)
            .last("LIMIT 1"));
        return latestMigrationAudit != null && AUDIT_FAILED.equals(latestMigrationAudit.getAction());
    }

    private long activeKeyCount(Long projectId, KeyType keyType) {
        return apiKeyMapper.selectCount(Wrappers.<BaasApiKey>lambdaQuery()
            .eq(BaasApiKey::getProjectId, projectId)
            .eq(BaasApiKey::getKeyType, keyType)
            .eq(BaasApiKey::getStatus, "ACTIVE"));
    }

    private Map<String, PhysicalTable> readPreflightTables(BaasProject current, LockHandle handle,
            JdbcTemplate lockedJdbc, MigrationSource source, Long operatorUserId) {
        try {
            return readSystemTables(lockedJdbc, current.getDbName());
        }
        catch (RuntimeException failure) {
            recordPreflightFailure(current, handle, source, operatorUserId);
            log.warn("system table migration preflight failed projectId={} errorType={}", current.getId(),
                    failure.getClass().getSimpleName());
            throw new DdlConflictException("系统表迁移失败");
        }
    }

    private boolean hasUnsignedOverflow(BaasProject current, JdbcTemplate lockedJdbc,
            Map<String, PhysicalTable> tables) {
        for (String tableName : SystemTableManifest.SYSTEM_TABLE_NAMES) {
            if (SystemTableManifest.tableMatches(tableName, tables.get(tableName), false)) {
                continue;
            }
            Long overflow = lockedJdbc.queryForObject(
                    SystemTableManifest.unsignedBoundsCheckSql(current.getDbName(), tableName), Long.class);
            if (overflow != null && overflow > 0) {
                return true;
            }
        }
        return false;
    }

    private SystemTableMigrationResult recoverCurrentProject(BaasProject current, LockHandle handle,
            MigrationSource source, Long operatorUserId) {
        if (current.getStatus() != ProjectStatus.ACTIVE) {
            lockExecutor.assertStillHeld(handle);
            transitionWithEpochAndAudit(current.getId(), current.getStatus(), ProjectStatus.ACTIVE,
                    source, operatorUserId, 0, true);
        }
        return new SystemTableMigrationResult(ProjectStatus.ACTIVE.name(), false);
    }

    private void recordPreflightFailure(BaasProject current, LockHandle handle, MigrationSource source,
            Long operatorUserId) {
        if (current.getStatus() != ProjectStatus.FAILED) {
            lockExecutor.assertStillHeld(handle);
            transitionWithEpochAndAudit(current.getId(), current.getStatus(), ProjectStatus.FAILED,
                    source, operatorUserId, 0, false);
        }
    }

    private void markFailedVerified(Long projectId, long heldEpoch, LockHandle handle,
            MigrationSource source, Long operatorUserId, int migratedTableCount) {
        lockExecutor.assertStillHeld(handle);
        transactionTemplate.executeWithoutResult(status -> {
            fencingGuard.verifyEpochInTx(projectId, heldEpoch);
            fencingGuard.incrementEpochInTx(projectId);
            updateStatus(projectId, ProjectStatus.MIGRATING, ProjectStatus.FAILED);
            audit(projectId, operatorUserId, source, false, migratedTableCount);
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

    private void transitionVerifiedWithAudit(Long projectId, long heldEpoch, ProjectStatus from,
            ProjectStatus to, MigrationSource source, Long operatorUserId, int migratedTableCount) {
        transactionTemplate.executeWithoutResult(status -> {
            fencingGuard.verifyEpochInTx(projectId, heldEpoch);
            fencingGuard.incrementEpochInTx(projectId);
            updateStatus(projectId, from, to);
            audit(projectId, operatorUserId, source, true, migratedTableCount);
        });
    }

    private void transitionWithEpochAndAudit(Long projectId, ProjectStatus from, ProjectStatus to,
            MigrationSource source, Long operatorUserId, int migratedTableCount, boolean success) {
        transactionTemplate.executeWithoutResult(status -> {
            fencingGuard.incrementEpochInTx(projectId);
            updateStatus(projectId, from, to);
            audit(projectId, operatorUserId, source, success, migratedTableCount);
        });
    }

    private void updateStatus(Long projectId, ProjectStatus from, ProjectStatus to) {
        int updated = projectMapper.update(null, Wrappers.<BaasProject>lambdaUpdate()
            .eq(BaasProject::getId, projectId)
            .eq(BaasProject::getStatus, from)
            .set(BaasProject::getStatus, to));
        if (updated != 1) {
            throw new IllegalStateException("project status transition race: " + from + " -> " + to);
        }
    }

    private void audit(Long projectId, Long operatorUserId, MigrationSource source,
            boolean success, int migratedTableCount) {
        BaasAuditLog auditLog = new BaasAuditLog();
        auditLog.setProjectId(projectId);
        auditLog.setOperatorUserId(operatorUserId);
        auditLog.setAction(success ? AUDIT_SUCCESS : AUDIT_FAILED);
        auditLog.setDetail("source=" + source.name() + ",result=" + (success ? "SUCCESS" : "FAILED")
                + ",migratedTables=" + migratedTableCount);
        auditLog.setLevel(success ? "INFO" : "HIGH");
        auditLogMapper.insert(auditLog);
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
