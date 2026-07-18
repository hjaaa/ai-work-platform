/*
 *
 *      Copyright (c) 2018-2025, lengleng All rights reserved.
 *
 */

package com.aiwork.baas.service;

import com.aiwork.baas.ddl.RequestFingerprint;
import com.aiwork.baas.entity.BaasDdlLog;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.enums.DdlLogStatus;
import com.aiwork.baas.entity.enums.DdlOperationType;
import com.aiwork.baas.entity.enums.ProjectStatus;
import com.aiwork.baas.mapper.BaasDdlLogMapper;
import com.aiwork.baas.mapper.BaasProjectMapper;
import com.aiwork.baas.provision.PhysicalPreconditions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 定时对账任务单元测试。
 *
 * @author ai-work
 * @date 2026/07/19
 */
@ExtendWith(MockitoExtension.class)
class ScheduledReconcileJobTest {

    @Mock
    private PhysicalPreconditions physicalPreconditions;

    @Mock
    private ReconcileService reconcileService;

    @Mock
    private BaasProjectMapper projectMapper;

    @Mock
    private BaasDdlLogMapper ddlLogMapper;

    private ScheduledReconcileJob job;

    @BeforeEach
    void setUp() {
        job = new ScheduledReconcileJob(physicalPreconditions, reconcileService, projectMapper, ddlLogMapper);
    }

    @Test
    void scheduledScanIsDisabledByDefault() {
        job.scheduledScan();

        verifyNoInteractions(physicalPreconditions, reconcileService, projectMapper, ddlLogMapper);
    }

    @Test
    void scanOnceChecksPhysicalPreconditionsBeforeScanningAndReusesLeftover() {
        BaasProject project = project(7L);
        BaasDdlLog leftover = leftover("22222222-2222-2222-2222-222222222222", "f".repeat(64));
        when(projectMapper.selectList(any())).thenReturn(List.of(project));
        when(ddlLogMapper.selectOne(any())).thenReturn(leftover);

        job.scanOnce();

        InOrder order = inOrder(physicalPreconditions, projectMapper, ddlLogMapper, reconcileService);
        order.verify(physicalPreconditions).assertSatisfied();
        order.verify(projectMapper).selectList(any());
        order.verify(ddlLogMapper).selectOne(any());
        order.verify(reconcileService)
            .reconcile(project, leftover.getOperationId(), ReconcileService.TRIGGER_SCHEDULED,
                    leftover.getRequestHash());
    }

    @Test
    void scanOnceCreatesVersionedFingerprintWhenNoLeftoverExists() {
        BaasProject project = project(9L);
        when(projectMapper.selectList(any())).thenReturn(List.of(project));
        when(ddlLogMapper.selectOne(any())).thenReturn(null);
        ArgumentCaptor<String> operationIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> requestHashCaptor = ArgumentCaptor.forClass(String.class);

        job.scanOnce();

        verify(reconcileService).reconcile(eq(project), operationIdCaptor.capture(),
                eq(ReconcileService.TRIGGER_SCHEDULED), requestHashCaptor.capture());
        assertThat(requestHashCaptor.getValue())
            .isEqualTo(RequestFingerprint.scheduledReconcile(project.getId(), operationIdCaptor.getValue()));
    }

    @Test
    void disabledScheduledScanNeverDelegatesEvenWhenIntervalFires() {
        ReflectionTestUtils.setField(job, "enabled", false);

        job.scheduledScan();

        verify(reconcileService, never()).reconcile(any(), any(), any(), any());
    }

    private static BaasProject project(Long id) {
        BaasProject project = new BaasProject();
        project.setId(id);
        project.setStatus(ProjectStatus.ACTIVE);
        return project;
    }

    private static BaasDdlLog leftover(String operationId, String requestHash) {
        BaasDdlLog log = new BaasDdlLog();
        log.setOperationId(operationId);
        log.setRequestHash(requestHash);
        log.setOperationType(DdlOperationType.RECONCILE.code());
        log.setTriggerSource(ReconcileService.TRIGGER_SCHEDULED);
        log.setStatus(DdlLogStatus.FAILED.name());
        return log;
    }

}
