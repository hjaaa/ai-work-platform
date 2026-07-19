/*
 *
 *      Copyright (c) 2018-2025, lengleng All rights reserved.
 *
 */

package com.aiwork.baas.service;

import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.enums.ProjectStatus;
import com.aiwork.baas.mapper.BaasProjectMapper;
import com.aiwork.baas.provision.PhysicalPreconditions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
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

    private ScheduledReconcileJob job;

    @BeforeEach
    void setUp() {
        job = new ScheduledReconcileJob(physicalPreconditions, reconcileService, projectMapper);
    }

    @Test
    void scheduledScanIsDisabledByDefault() {
        job.scheduledScan();

        verifyNoInteractions(physicalPreconditions, reconcileService, projectMapper);
    }

    @Test
    void scanOnceChecksPhysicalPreconditionsBeforeDelegatingLockedDecision() {
        BaasProject project = project(7L);
        when(projectMapper.selectList(any())).thenReturn(List.of(project));

        job.scanOnce();

        InOrder order = inOrder(physicalPreconditions, projectMapper, reconcileService);
        order.verify(physicalPreconditions).assertSatisfied();
        order.verify(projectMapper).selectList(any());
        order.verify(reconcileService).scheduledReconcile(project);
    }

    @Test
    void disabledScheduledScanNeverDelegatesEvenWhenIntervalFires() {
        ReflectionTestUtils.setField(job, "enabled", false);

        job.scheduledScan();

        verify(reconcileService, never()).scheduledReconcile(any());
    }

    private static BaasProject project(Long id) {
        BaasProject project = new BaasProject();
        project.setId(id);
        project.setStatus(ProjectStatus.ACTIVE);
        return project;
    }
}
