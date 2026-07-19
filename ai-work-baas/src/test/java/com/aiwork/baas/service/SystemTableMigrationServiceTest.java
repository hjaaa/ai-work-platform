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
import com.aiwork.baas.ddl.lock.ProjectDdlLockExecutor;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.exception.DdlConflictException;
import com.aiwork.baas.mapper.BaasApiKeyMapper;
import com.aiwork.baas.mapper.BaasAuditLogMapper;
import com.aiwork.baas.mapper.BaasJwtKeyMapper;
import com.aiwork.baas.mapper.BaasProjectMapper;
import com.aiwork.baas.provision.PhysicalPreconditions;
import com.aiwork.baas.provision.ProvisionerDataSourceHolder;
import com.aiwork.baas.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 系统表迁移调度开关与物理前置条件契约测试。
 *
 * @author ai-work
 * @date 2026/07/19
 */
class SystemTableMigrationServiceTest {

    @Test
    void scheduledScanRefreshesReadinessAndStopsBeforeProjectQueryWhenUnsatisfied() {
        BaasProjectMapper projectMapper = mock(BaasProjectMapper.class);
        AtomicInteger probes = new AtomicInteger();
        PhysicalPreconditions preconditions = new PhysicalPreconditions(() -> {
            probes.incrementAndGet();
            return 8192L;
        });
        SystemTableMigrationService service = service(projectMapper, preconditions, true);

        service.scheduledScan();

        assertThat(probes).hasValue(1);
        verify(projectMapper, never()).selectList(any());
    }

    @Test
    void disabledScheduledScanDoesNotProbeOrQuery() {
        BaasProjectMapper projectMapper = mock(BaasProjectMapper.class);
        AtomicInteger probes = new AtomicInteger();
        PhysicalPreconditions preconditions = new PhysicalPreconditions(() -> {
            probes.incrementAndGet();
            return 16384L;
        });
        SystemTableMigrationService service = service(projectMapper, preconditions, false);

        service.scheduledScan();

        assertThat(probes).hasValue(0);
        verifyNoInteractions(projectMapper);
    }

    @Test
    void manualMigrationFailsClosedBeforeLockWithoutLeakingReadinessDetails() {
        BaasProjectMapper projectMapper = mock(BaasProjectMapper.class);
        ProjectDdlLockExecutor lockExecutor = mock(ProjectDdlLockExecutor.class);
        PhysicalPreconditions preconditions = new PhysicalPreconditions(() -> {
            throw new IllegalStateException("jdbc:mysql://secret?password=credential");
        });
        BaasProject project = new BaasProject();
        project.setId(1L);
        SystemTableMigrationService service = service(projectMapper, lockExecutor, preconditions, true);

        assertThatThrownBy(() -> service.migrate(project))
            .isInstanceOf(DdlConflictException.class)
            .hasMessage("系统表迁移失败")
            .hasMessageNotContaining("credential");
        verifyNoInteractions(lockExecutor, projectMapper);
    }

    private SystemTableMigrationService service(BaasProjectMapper projectMapper,
            PhysicalPreconditions preconditions, boolean enabled) {
        return service(projectMapper, mock(ProjectDdlLockExecutor.class), preconditions, enabled);
    }

    private SystemTableMigrationService service(BaasProjectMapper projectMapper,
            ProjectDdlLockExecutor lockExecutor, PhysicalPreconditions preconditions, boolean enabled) {
        when(projectMapper.selectList(any())).thenReturn(java.util.List.of());
        ProvisionerDataSourceHolder holder = new ProvisionerDataSourceHolder(new DriverManagerDataSource());
        return new SystemTableMigrationService(projectMapper, mock(BaasApiKeyMapper.class),
                mock(BaasJwtKeyMapper.class), mock(BaasAuditLogMapper.class), lockExecutor,
                mock(DdlFencingGuard.class), preconditions, mock(TransactionTemplate.class),
                mock(CurrentUserProvider.class), holder, enabled);
    }

}
