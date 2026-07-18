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

import com.aiwork.baas.ddl.lock.DdlLockManager;
import com.aiwork.baas.ddl.lock.ProjectDdlLockExecutor;
import com.aiwork.baas.entity.enums.DdlOperationType;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

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

}
