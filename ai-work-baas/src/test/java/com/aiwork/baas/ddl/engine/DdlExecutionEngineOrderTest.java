/*
 *
 *      Copyright (c) 2018-2026, lengleng All rights reserved.
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

import com.aiwork.baas.ddl.lock.DdlLockManager;
import com.aiwork.baas.ddl.lock.DdlLockBusyException;
import com.aiwork.baas.ddl.lock.ProjectDdlLockExecutor;
import com.aiwork.baas.entity.BaasDdlLog;
import com.aiwork.baas.entity.enums.DdlLogStatus;
import com.aiwork.baas.entity.enums.DdlOperationType;
import com.aiwork.baas.entity.enums.DdlStep;
import com.aiwork.baas.mapper.BaasDdlLogMapper;
import com.aiwork.baas.provision.PhysicalPreconditions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DdlExecutionEngineOrderTest {

    @Mock
    private BaasDdlLogMapper ddlLogMapper;

    @Mock
    private DdlLockManager lockManager;

    @Mock
    private ProjectDdlLockExecutor lockExecutor;

    @Mock
    private DdlFencingGuard fencingGuard;

    @Mock
    private PhysicalPreconditions physicalPreconditions;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @Test
    void physicalPreconditionsFailBeforeLogsLocksMetadataOrSideEffects() {
        DdlExecutionEngine engine = new DdlExecutionEngine(ddlLogMapper, lockManager, lockExecutor, fencingGuard,
                physicalPreconditions, transactionTemplate, objectMapper);
        doThrow(new IllegalStateException("physical preconditions failed")).when(physicalPreconditions)
            .assertSatisfied();
        DdlOperationSpec spec = new DdlOperationSpec(1L, "operation", DdlOperationType.CREATE, "demo", null,
                "a".repeat(64), null, "CREATE TABLE ...(?)");

        DdlWork work = new DdlWork() {
            @Override
            public void validateInLock(DdlWorkContext context) {
            }

            @Override
            public ObjectNode perform(DdlWorkContext context) {
                return objectMapper.createObjectNode();
            }
        };

        assertThatThrownBy(() -> engine.execute(spec, work))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("physical preconditions failed");
        verifyNoInteractions(ddlLogMapper, lockManager, lockExecutor, fencingGuard, transactionTemplate, objectMapper);
    }

    @Test
    void checkpointCommitAckFailureUsesPersistedMonotonicStep() {
        DdlExecutionEngine engine = new DdlExecutionEngine(ddlLogMapper, lockManager, lockExecutor, fencingGuard,
                physicalPreconditions, transactionTemplate, new ObjectMapper());
        DdlOperationSpec spec = new DdlOperationSpec(1L, "operation", DdlOperationType.CREATE, "demo", null,
                "a".repeat(64), null, "CREATE TABLE ...(?)");
        DdlWorkContext context = new DdlWorkContext(engine, spec, null, OwnershipBranch.NEW_OPERATION, null, null);
        context.setOwnership("owner", 7L, 11L, DdlStep.PREPARED);
        BaasDdlLog committed = committedLog(spec, DdlLogStatus.RUNNING, DdlStep.DDL_APPLIED);

        when(lockManager.stillHeld(null)).thenReturn(true);
        doThrow(new IllegalStateException("commit acknowledgement lost")).when(transactionTemplate)
            .executeWithoutResult(any());
        when(ddlLogMapper.selectByProjectAndOperation(1L, "operation")).thenReturn(committed);

        context.advanceToDdlApplied();

        assertThat(context.currentStep()).isEqualTo(DdlStep.DDL_APPLIED);
    }

    @Test
    void postCallbackLockExpiryReturnsCommittedSuccessSnapshot() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        DdlExecutionEngine engine = new DdlExecutionEngine(ddlLogMapper, lockManager, lockExecutor, fencingGuard,
                physicalPreconditions, transactionTemplate, mapper);
        DdlOperationSpec spec = new DdlOperationSpec(1L, "operation", DdlOperationType.CREATE, "demo", null,
                "a".repeat(64), null, "CREATE TABLE ...(?)");
        BaasDdlLog committed = committedLog(spec, DdlLogStatus.SUCCESS, DdlStep.METADATA_APPLIED);
        committed.setResultSnapshot("{\"ok\":true}");
        when(ddlLogMapper.selectByProjectAndOperation(1L, "operation")).thenReturn(null, committed);
        doThrow(new DdlLockBusyException("post callback lock expired")).when(lockExecutor)
            .execute(eq(1L), any());

        ObjectNode result = engine.execute(spec, new DdlWork() {
            @Override
            public void validateInLock(DdlWorkContext context) {
            }

            @Override
            public ObjectNode perform(DdlWorkContext context) {
                throw new AssertionError("committed SUCCESS should bypass work");
            }
        });

        assertThat(result.path("ok").asBoolean()).isTrue();
    }

    private BaasDdlLog committedLog(DdlOperationSpec spec, DdlLogStatus status, DdlStep step) {
        BaasDdlLog log = new BaasDdlLog();
        log.setId(11L);
        log.setProjectId(spec.projectId());
        log.setOperationId(spec.operationId());
        log.setRequestHash(spec.requestHash());
        log.setOwnerToken("owner");
        log.setFenceEpoch(7L);
        log.setStatus(status.name());
        log.setStep(step.name());
        return log;
    }

}
