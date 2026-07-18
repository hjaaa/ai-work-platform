/*
 *
 *      Copyright (c) 2018-2025, lengleng All rights reserved.
 *
 */

package com.aiwork.baas.service;

import com.aiwork.baas.ddl.lock.DdlLockManager;
import com.aiwork.baas.ddl.lock.LockHandle;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.enums.ProjectStatus;
import com.aiwork.baas.entity.enums.ProvisionStep;
import com.aiwork.baas.exception.DdlConflictException;
import com.aiwork.baas.mapper.BaasProjectMapper;
import com.aiwork.baas.provision.ProjectProvisioner;
import com.aiwork.baas.support.PlanBProjectIntegrationTestSupport;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 项目生命周期与表 DDL 共用锁序的真实 MySQL/Redis 集成测试。
 *
 * @author ai-work
 * @date 2026/07/18
 */
class ProjectLifecycleLockingTest extends PlanBProjectIntegrationTestSupport {

    @Autowired
    private ProjectLifecycleService lifecycleService;

    @Autowired
    private DdlLockManager lockManager;

    @Autowired
    private BaasProjectMapper projectMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoSpyBean
    private ProjectProvisioner provisioner;

    @Override
    protected String projectNamePrefix() {
        return "lifecycle-lock";
    }

    @Test
    void createAndRetryProvisionHoldProjectDdlLockDuringPhysicalWork() {
        AtomicBoolean createLockObserved = new AtomicBoolean();
        Mockito.doAnswer(invocation -> {
            String databaseName = invocation.getArgument(0);
            BaasProject current = projectMapper.selectOne(Wrappers.<BaasProject>lambdaQuery()
                .eq(BaasProject::getDbName, databaseName));
            createLockObserved.set(current != null && redisTemplate.hasKey(DdlLockManager.lockKey(current.getId())));
            return invocation.callRealMethod();
        }).when(provisioner).createDatabase(Mockito.anyString());
        BaasProject created;
        try {
            created = lifecycleService.createProject("lk-create", 1L).project();
        }
        finally {
            Mockito.reset(provisioner);
        }
        assertThat(createLockObserved).isTrue();

        projectMapper.update(null, Wrappers.<BaasProject>lambdaUpdate()
            .eq(BaasProject::getId, created.getId())
            .set(BaasProject::getStatus, ProjectStatus.FAILED)
            .set(BaasProject::getProvisionStep, ProvisionStep.DB_CREATED.name()));
        AtomicBoolean retryLockObserved = new AtomicBoolean();
        Mockito.doAnswer(invocation -> {
            retryLockObserved.set(redisTemplate.hasKey(DdlLockManager.lockKey(created.getId())));
            return invocation.callRealMethod();
        })
            .when(provisioner)
            .createRuntimeUser(Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
        try {
            lifecycleService.retryProvision(created.getId());
        }
        finally {
            Mockito.reset(provisioner);
        }

        assertThat(retryLockObserved).isTrue();
        assertThat(projectMapper.selectById(created.getId()).getStatus()).isEqualTo(ProjectStatus.ACTIVE);
    }

    @Test
    void deleteProjectBlockedWhileDdlLockHeld() {
        long epochBefore = projectMapper.selectById(project.getId()).getDdlFenceEpoch();
        LockHandle blocker = lockManager.tryAcquire(project.getId());
        try {
            assertThatThrownBy(() -> lifecycleService.deleteProject(project.getId(), 1L))
                .isInstanceOf(DdlConflictException.class);
            assertThat(projectMapper.selectById(project.getId()).getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        }
        finally {
            lockManager.release(blocker);
        }

        lifecycleService.deleteProject(project.getId(), 1L);
        BaasProject deleting = projectMapper.selectById(project.getId());
        assertThat(deleting.getStatus()).isEqualTo(ProjectStatus.DELETING);
        assertThat(deleting.getDdlFenceEpoch()).isEqualTo(epochBefore + 1);
    }

    @Test
    void physicalCleanupBlockedWhileDdlLockHeldAndRunsAfterRelease() {
        lifecycleService.deleteProject(project.getId(), 1L);
        projectMapper.update(null, Wrappers.<BaasProject>lambdaUpdate()
            .eq(BaasProject::getId, project.getId())
            .set(BaasProject::getDeleteAfter, LocalDateTime.now().minusDays(1)));
        BaasProject due = projectMapper.selectById(project.getId());
        long epochBeforeCleanup = due.getDdlFenceEpoch();

        LockHandle blocker = lockManager.tryAcquire(project.getId());
        try {
            lifecycleService.physicallyCleanup(due);
            assertThat(projectMapper.selectById(project.getId()).getStatus()).isEqualTo(ProjectStatus.DELETING);
            assertThat(projectMapper.selectById(project.getId()).getDdlFenceEpoch()).isEqualTo(epochBeforeCleanup);
        }
        finally {
            lockManager.release(blocker);
        }

        lifecycleService.physicallyCleanup(due);

        BaasProject deleted = projectMapper.selectById(project.getId());
        assertThat(deleted.getStatus()).isEqualTo(ProjectStatus.DELETED);
        assertThat(deleted.getDdlFenceEpoch()).isEqualTo(epochBeforeCleanup + 1);
    }

}
