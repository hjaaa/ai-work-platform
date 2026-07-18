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

package com.aiwork.baas.ddl.engine;

import com.aiwork.baas.ddl.lock.DdlLockBusyException;
import com.aiwork.baas.ddl.lock.DdlLockManager;
import com.aiwork.baas.ddl.lock.LockHandle;
import com.aiwork.baas.ddl.lock.ProjectDdlLockExecutor;
import com.aiwork.baas.entity.BaasDdlLog;
import com.aiwork.baas.entity.enums.DdlLogStatus;
import com.aiwork.baas.entity.enums.DdlStep;
import com.aiwork.baas.exception.DdlConflictException;
import com.aiwork.baas.exception.DdlExecutionException;
import com.aiwork.baas.mapper.BaasDdlLogMapper;
import com.aiwork.baas.provision.PhysicalPreconditions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * DDL 执行引擎:spec §9.2「统一入口顺序 + 加锁与所有权顺序 + 四分支 + 检查点 + fencing」的唯一实现。
 * 所有表管理/ACL/对账/清理操作一律经 execute() 进入。
 *
 * @author ai-work
 * @date 2026/07/18
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DdlExecutionEngine {

    private static final String FAILURE_FINALIZATION_ERROR_CODE = "DDL_FAILURE_FINALIZATION_FAILED";

    private final BaasDdlLogMapper ddlLogMapper;

    private final DdlLockManager lockManager;

    private final ProjectDdlLockExecutor lockExecutor;

    private final DdlFencingGuard fencingGuard;

    private final PhysicalPreconditions physicalPreconditions;

    private final TransactionTemplate transactionTemplate;

    private final ObjectMapper objectMapper;

    /** DDL 专用执行超时(spec §13,默认 5 分钟,独立于数据面 5 秒);超时结果不确定,由探测式重试确认。 */
    @Value("${baas.ddl.execute-timeout-seconds:300}")
    private int ddlTimeoutSeconds;

    int ddlTimeoutSeconds() {
        return ddlTimeoutSeconds;
    }

    public ObjectNode execute(DdlOperationSpec spec, DdlWork work) {
        // Task 4 cross-check / spec §9.1:任何日志、锁、元数据或项目库副作用前先 fail-closed。
        physicalPreconditions.assertSatisfied();

        // 统一入口顺序(spec §9.2):快速路径,SUCCESS 重放不取任何锁。
        BaasDdlLog fastPath = ddlLogMapper.selectByProjectAndOperation(spec.projectId(), spec.operationId());
        if (fastPath != null) {
            requireMatchingFingerprint(fastPath, spec);
            if (isStatus(fastPath, DdlLogStatus.SUCCESS)) {
                return readSnapshot(fastPath);
            }
            if (isStatus(fastPath, DdlLogStatus.RUNNING)
                    && lockManager.isHeldBy(spec.projectId(), fastPath.getOwnerToken())) {
                throw new DdlConflictException("该项目有 DDL 操作进行中");
            }
        }

        try {
            return lockExecutor.execute(spec.projectId(),
                    (handle, connection) -> runInLock(spec, work, handle, connection));
        }
        catch (DdlLockBusyException busy) {
            throw new DdlConflictException("该项目有 DDL 操作进行中");
        }
    }

    boolean lockStillHeld(LockHandle handle) {
        return lockManager.stillHeld(handle);
    }

    private ObjectNode runInLock(DdlOperationSpec spec, DdlWork work, LockHandle handle, Connection connection)
            throws Exception {
        if (!lockManager.stillHeld(handle)) {
            throw new DdlConflictException("Redis 锁校验失败,中止执行");
        }

        // 锁内重读日志并分类(spec §9.2 六类)。
        BaasDdlLog inLock = ddlLogMapper.selectByProjectAndOperation(spec.projectId(), spec.operationId());
        OwnershipBranch branch;
        if (inLock == null) {
            branch = OwnershipBranch.NEW_OPERATION;
        }
        else {
            requireMatchingFingerprint(inLock, spec);
            if (isStatus(inLock, DdlLogStatus.SUCCESS)) {
                // 与快速路径竞态时在此兜住:返回快照。
                return readSnapshot(inLock);
            }
            if (isStatus(inLock, DdlLogStatus.RUNNING)) {
                if (lockManager.isHeldBy(spec.projectId(), inLock.getOwnerToken())) {
                    throw new DdlConflictException("该项目有 DDL 操作进行中");
                }
                branch = OwnershipBranch.TAKE_OVER_RUNNING;
            }
            else if (isStatus(inLock, DdlLogStatus.FAILED)) {
                branch = OwnershipBranch.RETRY_FAILED;
            }
            else {
                branch = OwnershipBranch.CLAIM_PENDING;
            }
        }

        DdlWorkContext context = new DdlWorkContext(this, spec, connection, branch, inLock, handle);
        // 按分支重读现状并校验(spec §9.2:依赖现状的校验不得沿用锁外快照)。
        work.validateInLock(context);

        acquireOwnership(context, work, inLock);

        // 提交成功后锁内重读确认(spec §9.2:确认前不得产生任何项目库副作用)。
        BaasDdlLog confirmed = ddlLogMapper.selectByProjectAndOperation(spec.projectId(), spec.operationId());
        if (confirmed == null || !Objects.equals(confirmed.getOwnerToken(), context.ownerToken())
                || !isStatus(confirmed, DdlLogStatus.RUNNING)
                || !Objects.equals(confirmed.getFenceEpoch(), context.fenceEpoch())) {
            throw new DdlConflictException("所有权确认失败,放弃执行");
        }
        context.setCurrentStep(DdlStep.valueOf(confirmed.getStep()));
        context.assertLockStillHeld();

        try {
            return work.perform(context);
        }
        catch (StaleExecutorException stale) {
            throw stale;
        }
        catch (Exception exception) {
            RuntimeException translated = translateFailure(exception);
            markFailed(context, work, translated);
            throw translated;
        }
    }

    /**
     * 所有权短事务(spec §9.2):① 项目行 FOR UPDATE + epoch+1;② 分支 INSERT/CAS 一律写 fence_epoch;
     * ③ work.inOwnershipTx(表状态置位);任一失败整笔回滚(epoch 增量一并撤销)。
     */
    private void acquireOwnership(DdlWorkContext context, DdlWork work, BaasDdlLog existing) {
        DdlOperationSpec spec = context.spec();
        String newToken = context.lockHandle().ownerToken();
        transactionTemplate.executeWithoutResult(txStatus -> {
            long newEpoch = fencingGuard.incrementEpochInTx(spec.projectId());
            Long logId;
            DdlStep step;
            switch (context.branch()) {
                case NEW_OPERATION -> {
                    BaasDdlLog logRecord = new BaasDdlLog();
                    logRecord.setProjectId(spec.projectId());
                    logRecord.setOperationId(spec.operationId());
                    logRecord.setOperationType(spec.type().code());
                    logRecord.setTableName(spec.tableName());
                    logRecord.setTableId(spec.tableId());
                    logRecord.setRequestHash(spec.requestHash());
                    logRecord.setTriggerSource(spec.triggerSource());
                    logRecord.setDdlText(spec.ddlTextSanitized());
                    logRecord.setStep(DdlStep.PREPARED.name());
                    logRecord.setStatus(DdlLogStatus.RUNNING.name());
                    logRecord.setOwnerToken(newToken);
                    logRecord.setFenceEpoch(newEpoch);
                    logRecord.setRetryCount(0);
                    try {
                        ddlLogMapper.insert(logRecord);
                    }
                    catch (DuplicateKeyException duplicate) {
                        // 双层锁内不应发生;保守按并发冲突整笔回滚。
                        throw new DdlConflictException("并发创建同 operationId 日志");
                    }
                    logId = logRecord.getId();
                    step = DdlStep.PREPARED;
                }
                case RETRY_FAILED -> {
                    if (ddlLogMapper.casRetryFailed(existing.getId(), existing.getOwnerToken(), newToken,
                            newEpoch) != 1) {
                        throw new DdlConflictException("并发重试竞争失败");
                    }
                    logId = existing.getId();
                    step = DdlStep.valueOf(existing.getStep());
                }
                case TAKE_OVER_RUNNING -> {
                    if (ddlLogMapper.casTakeOverRunning(existing.getId(), existing.getOwnerToken(), newToken,
                            newEpoch) != 1) {
                        throw new DdlConflictException("陈旧 RUNNING 接管竞争失败");
                    }
                    logId = existing.getId();
                    step = DdlStep.valueOf(existing.getStep());
                }
                case CLAIM_PENDING -> {
                    if (ddlLogMapper.casClaimPending(existing.getId(), newToken, newEpoch) != 1) {
                        throw new DdlConflictException("PENDING 认领竞争失败");
                    }
                    logId = existing.getId();
                    step = DdlStep.valueOf(existing.getStep());
                }
                default -> throw new IllegalStateException("unknown branch");
            }
            context.setOwnership(newToken, newEpoch, logId, step);
            work.inOwnershipTx(context);
        });
    }

    void advanceToDdlApplied(DdlWorkContext context) {
        context.assertLockStillHeld();
        transactionTemplate.executeWithoutResult(txStatus -> {
            fencingGuard.verifyEpochInTx(context.spec().projectId(), context.fenceEpoch());
            if (ddlLogMapper.advanceStepGuarded(context.logId(), context.ownerToken(),
                    DdlStep.DDL_APPLIED.name()) != 1) {
                throw new StaleExecutorException("检查点推进被拒,本执行者已陈旧");
            }
        });
        context.setCurrentStep(DdlStep.DDL_APPLIED);
    }

    ObjectNode completeSuccess(DdlWorkContext context, Supplier<ObjectNode> metadataWrites) {
        context.assertLockStillHeld();
        ObjectNode[] snapshotHolder = new ObjectNode[1];
        transactionTemplate.executeWithoutResult(txStatus -> {
            fencingGuard.verifyEpochInTx(context.spec().projectId(), context.fenceEpoch());
            snapshotHolder[0] = metadataWrites.get();
            if (ddlLogMapper.finishGuarded(context.logId(), context.ownerToken(), context.fenceEpoch(),
                    DdlLogStatus.SUCCESS.name(), DdlStep.METADATA_APPLIED.name(), snapshotHolder[0].toString(),
                    null) != 1) {
                throw new StaleExecutorException("终态写入被拒,整笔回滚");
            }
        });
        context.setCurrentStep(DdlStep.METADATA_APPLIED);
        return snapshotHolder[0];
    }

    private RuntimeException translateFailure(Exception exception) {
        if (exception instanceof DataAccessException dataAccessException) {
            return DdlSqlFailureTranslator.translate(dataAccessException);
        }
        if (exception instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new DdlExecutionException("DDL_EXECUTION_FAILED", "UNKNOWN", 0);
    }

    private void markFailed(DdlWorkContext context, DdlWork work, Exception cause) {
        context.assertLockStillHeld();
        try {
            transactionTemplate.executeWithoutResult(txStatus -> {
                fencingGuard.verifyEpochInTx(context.spec().projectId(), context.fenceEpoch());
                if (ddlLogMapper.finishGuarded(context.logId(), context.ownerToken(), context.fenceEpoch(),
                        DdlLogStatus.FAILED.name(), context.currentStep().name(), null,
                        sanitizeError(cause)) != 1) {
                    throw new StaleExecutorException("FAILED 终态写入被拒");
                }
                work.onFailureTx(context);
            });
        }
        catch (StaleExecutorException stale) {
            throw stale;
        }
        catch (Exception secondary) {
            log.warn("mark ddl failed operationId={} errorType={} errorCode={}", context.spec().operationId(),
                    secondary.getClass().getSimpleName(), FAILURE_FINALIZATION_ERROR_CODE);
            throw new DdlExecutionException(FAILURE_FINALIZATION_ERROR_CODE, "UNKNOWN", 0);
        }
    }

    private void requireMatchingFingerprint(BaasDdlLog logRecord, DdlOperationSpec spec) {
        if (!Objects.equals(logRecord.getRequestHash(), spec.requestHash())) {
            throw new DdlConflictException("同 operationId 的请求内容不一致");
        }
    }

    private boolean isStatus(BaasDdlLog logRecord, DdlLogStatus status) {
        return status.name().equals(logRecord.getStatus());
    }

    private ObjectNode readSnapshot(BaasDdlLog logRecord) {
        try {
            if (logRecord.getResultSnapshot() == null) {
                return objectMapper.createObjectNode();
            }
            return (ObjectNode) objectMapper.readTree(logRecord.getResultSnapshot());
        }
        catch (Exception exception) {
            // 持久化快照可能包含表名/注释等元数据,不得把 Jackson 原始异常挂到 cause 交给框架日志。
            throw new DdlExecutionException("DDL_RESULT_SNAPSHOT_INVALID", "UNKNOWN", 0);
        }
    }

    /** 只生成稳定诊断码;禁止从 Throwable.message 提取任何文本(spec §11)。 */
    private String sanitizeError(Exception cause) {
        if (cause instanceof DdlConflictException) {
            return "DDL_DATA_CONFLICT";
        }
        if (cause instanceof DdlExecutionException execution) {
            return execution.errorCode() + ";sqlState=" + execution.sqlState()
                    + ";vendorCode=" + execution.vendorCode();
        }
        return "DDL_INTERNAL_FAILURE;errorType=" + cause.getClass().getSimpleName();
    }

}
