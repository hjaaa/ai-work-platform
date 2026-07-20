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

import com.aiwork.baas.ddl.engine.DdlExecutionEngine;
import com.aiwork.baas.ddl.engine.DdlFencingGuard;
import com.aiwork.baas.ddl.engine.DdlOperationSpec;
import com.aiwork.baas.ddl.lock.DdlLockBusyException;
import com.aiwork.baas.ddl.lock.DdlLockManager;
import com.aiwork.baas.ddl.lock.LockHandle;
import com.aiwork.baas.ddl.lock.ProjectDdlLockExecutor;
import com.aiwork.baas.entity.BaasDdlLog;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.BaasTable;
import com.aiwork.baas.entity.enums.DdlLogStatus;
import com.aiwork.baas.entity.enums.DdlOperationType;
import com.aiwork.baas.entity.enums.TableStatus;
import com.aiwork.baas.exception.DdlConflictException;
import com.aiwork.baas.mapper.BaasColumnMapper;
import com.aiwork.baas.mapper.BaasDdlLogMapper;
import com.aiwork.baas.mapper.BaasProjectMapper;
import com.aiwork.baas.mapper.BaasTableAclMapper;
import com.aiwork.baas.mapper.BaasTableMapper;
import com.aiwork.baas.provision.PhysicalPreconditions;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * DDL 维护调度器：认领、重试或接管 cleanup-drop，并兜底陈旧 HTTP RUNNING。
 *
 * @author ai-work
 * @date 2026/07/18
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DdlMaintenanceJob {

    private static final String STALE_RUNNING_ERROR_CODE = "STALE_RUNNING_TIMEOUT";

    private static final Set<DdlOperationType> HTTP_OPERATION_TYPES = EnumSet.of(DdlOperationType.CREATE,
            DdlOperationType.ALTER, DdlOperationType.DROP, DdlOperationType.ACL_CONFIG);

    private final PhysicalPreconditions physicalPreconditions;

    private final DdlExecutionEngine engine;

    private final DdlLockManager lockManager;

    private final ProjectDdlLockExecutor lockExecutor;

    private final DdlFencingGuard fencingGuard;

    private final TransactionTemplate transactionTemplate;

    private final BaasDdlLogMapper ddlLogMapper;

    private final BaasProjectMapper projectMapper;

    private final BaasTableMapper tableMapper;

    private final BaasColumnMapper columnMapper;

    private final BaasTableAclMapper aclMapper;

    private final ObjectMapper objectMapper;

    private final BoundedScanCursor<BaasDdlLog> cleanupScanCursor = new BoundedScanCursor<>();

    private final BoundedScanCursor<BaasDdlLog> staleScanCursor = new BoundedScanCursor<>();

    @Value("${baas.ddl.stale-running-minutes:10}")
    private int staleRunningMinutes;

    public void scheduledScan() {
        scanOnce();
    }

    @Scheduled(initialDelayString = "${baas.ddl.maintenance-interval-millis:300000}",
            fixedDelayString = "${baas.ddl.maintenance-interval-millis:300000}")
    public void scheduledCleanupScan() {
        physicalPreconditions.assertSatisfied();
        processCleanupRecords();
    }

    @Scheduled(initialDelayString = "${baas.ddl.maintenance-interval-millis:300000}",
            fixedDelayString = "${baas.ddl.maintenance-interval-millis:300000}")
    public void scheduledStaleScan() {
        physicalPreconditions.assertSatisfied();
        processStaleHttpRunning();
    }

    /** 执行一轮维护；物理前置条件是任何扫描、锁或元数据副作用前的第一动作。 */
    public void scanOnce() {
        physicalPreconditions.assertSatisfied();
        processCleanupRecords();
        processStaleHttpRunning();
    }

    private void processCleanupRecords() {
        List<BaasDdlLog> candidates = cleanupScanCursor.nextBatch(this::loadLatestCleanupRecords,
                this::loadCursorCleanupRecords, BaasDdlLog::getId);
        for (BaasDdlLog record : candidates) {
            try {
                if (shouldSkipCleanup(record)) {
                    continue;
                }
                BaasProject project = projectMapper.selectById(record.getProjectId());
                if (project == null) {
                    continue;
                }
                DdlOperationSpec spec = new DdlOperationSpec(record.getProjectId(), record.getOperationId(),
                        DdlOperationType.CLEANUP_DROP, record.getTableName(), record.getTableId(),
                        record.getRequestHash(), null, null);
                engine.execute(spec, new CleanupDropWork(project, record, projectMapper, tableMapper, columnMapper,
                        aclMapper, objectMapper));
            }
            catch (Exception exception) {
                log.warn("cleanup processing skipped operationId={} errorType={}", record.getOperationId(),
                        exception.getClass().getSimpleName());
            }
        }
    }

    private List<BaasDdlLog> loadLatestCleanupRecords(int limit) {
        return loadCleanupRecords(0, limit);
    }

    private List<BaasDdlLog> loadCursorCleanupRecords(long beforeId, int limit) {
        return loadCleanupRecords(beforeId, limit);
    }

    private List<BaasDdlLog> loadCleanupRecords(long beforeId, int limit) {
        return ddlLogMapper.selectList(Wrappers.<BaasDdlLog>lambdaQuery()
            .eq(BaasDdlLog::getOperationType, DdlOperationType.CLEANUP_DROP.code())
            .in(BaasDdlLog::getStatus, DdlLogStatus.PENDING.name(), DdlLogStatus.FAILED.name(),
                    DdlLogStatus.RUNNING.name())
            .lt(beforeId > 0, BaasDdlLog::getId, beforeId)
            .orderByDesc(BaasDdlLog::getId)
            .last("LIMIT " + limit));
    }

    private boolean shouldSkipCleanup(BaasDdlLog record) {
        if (DdlLogStatus.RUNNING.name().equals(record.getStatus())) {
            return lockManager.isHeldBy(record.getProjectId(), record.getOwnerToken());
        }
        return DdlLogStatus.PENDING.name().equals(record.getStatus()) && !pendingLooksDueOrInvalid(record);
    }

    /** 锁外仅做减载；目标不匹配也必须进入锁内完成安全 no-op。 */
    private boolean pendingLooksDueOrInvalid(BaasDdlLog record) {
        BaasTable table = tableMapper.selectById(record.getTableId());
        if (table == null || !Objects.equals(table.getProjectId(), record.getProjectId())
                || !Objects.equals(table.getTableName(), record.getTableName())
                || !TableStatus.DELETED.name().equals(table.getStatus())) {
            return true;
        }
        return table.getDeleteAfter() != null && table.getDeleteAfter().isBefore(LocalDateTime.now());
    }

    private void processStaleHttpRunning() {
        LocalDateTime threshold = staleThreshold();
        List<BaasDdlLog> candidates = staleScanCursor.nextBatch(
                limit -> loadStaleHttpRunning(0, threshold, limit),
                (beforeId, limit) -> loadStaleHttpRunning(beforeId, threshold, limit), BaasDdlLog::getId);
        for (BaasDdlLog record : candidates) {
            try {
                if (lockManager.isHeldBy(record.getProjectId(), record.getOwnerToken())) {
                    continue;
                }
                lockExecutor.execute(record.getProjectId(), (handle, connection) -> {
                    forceFailInLock(record.getId(), handle);
                    return null;
                });
            }
            catch (DdlLockBusyException busy) {
                if (log.isDebugEnabled()) {
                    log.debug("project {} ddl lock busy, skip stale fallback", record.getProjectId());
                }
            }
            catch (Exception exception) {
                log.warn("stale fallback failed operationId={} errorType={}", record.getOperationId(),
                        exception.getClass().getSimpleName());
            }
        }
    }

    private List<BaasDdlLog> loadStaleHttpRunning(long beforeId, LocalDateTime threshold, int limit) {
        return ddlLogMapper.selectList(Wrappers.<BaasDdlLog>lambdaQuery()
            .eq(BaasDdlLog::getStatus, DdlLogStatus.RUNNING.name())
            .in(BaasDdlLog::getOperationType, HTTP_OPERATION_TYPES.stream().map(DdlOperationType::code).toList())
            .lt(BaasDdlLog::getUpdateTime, threshold)
            .lt(beforeId > 0, BaasDdlLog::getId, beforeId)
            .orderByDesc(BaasDdlLog::getId)
            .last("LIMIT " + limit));
    }

    private void forceFailInLock(Long logId, LockHandle handle) {
        BaasDdlLog current = ddlLogMapper.selectById(logId);
        if (!isExactStaleHttpRunning(current, handle, staleThreshold())
                || lockManager.isHeldBy(current.getProjectId(), current.getOwnerToken())) {
            return;
        }

        lockExecutor.assertStillHeld(handle);
        transactionTemplate.executeWithoutResult(status -> {
            long newEpoch = fencingGuard.incrementEpochInTx(current.getProjectId());
            if (ddlLogMapper.casForceFailRunning(current.getId(), current.getOwnerToken(), current.getFenceEpoch(),
                    newEpoch, STALE_RUNNING_ERROR_CODE) != 1) {
                throw new DdlConflictException("陈旧 RUNNING 兜底竞争失败");
            }
            settleTableState(current);
        });
    }

    private boolean isExactStaleHttpRunning(BaasDdlLog current, LockHandle handle, LocalDateTime threshold) {
        if (current == null || !Objects.equals(current.getProjectId(), handle.projectId())
                || !DdlLogStatus.RUNNING.name().equals(current.getStatus()) || current.getOwnerToken() == null
                || current.getFenceEpoch() == null || current.getUpdateTime() == null
                || !current.getUpdateTime().isBefore(threshold)) {
            return false;
        }
        try {
            return HTTP_OPERATION_TYPES.contains(DdlOperationType.fromCode(current.getOperationType()));
        }
        catch (IllegalArgumentException invalidType) {
            return false;
        }
    }

    private void settleTableState(BaasDdlLog current) {
        DdlOperationType type = DdlOperationType.fromCode(current.getOperationType());
        if (current.getTableId() == null || type == DdlOperationType.DROP) {
            return;
        }
        if (type == DdlOperationType.CREATE) {
            updateTableState(current.getTableId(), TableStatus.CREATING, TableStatus.FAILED,
                    "陈旧 create 表状态兜底竞争失败");
            return;
        }
        if (type == DdlOperationType.ALTER || aclHasDdlIntent(type, current.getDdlText())) {
            updateTableState(current.getTableId(), TableStatus.ALTERING, TableStatus.CONFLICT,
                    "陈旧 alter 表状态兜底竞争失败");
        }
    }

    private boolean aclHasDdlIntent(DdlOperationType type, String ddlText) {
        return type == DdlOperationType.ACL_CONFIG && ddlText != null && !ddlText.isBlank();
    }

    private void updateTableState(Long tableId, TableStatus expected, TableStatus target, String errorMessage) {
        int affectedRows = tableMapper.update(null, Wrappers.<BaasTable>lambdaUpdate()
            .eq(BaasTable::getId, tableId)
            .eq(BaasTable::getStatus, expected.name())
            .set(BaasTable::getStatus, target.name()));
        if (affectedRows != 1) {
            throw new DdlConflictException(errorMessage);
        }
    }

    private LocalDateTime staleThreshold() {
        return LocalDateTime.now().minusMinutes(staleRunningMinutes);
    }

}
