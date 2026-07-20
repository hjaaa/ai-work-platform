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

import com.aiwork.baas.datasource.ProjectDataSourceRegistry;
import com.aiwork.baas.ddl.lock.AdvisoryLockTemplate;
import com.aiwork.baas.ddl.lock.DdlLockManager;
import com.aiwork.baas.ddl.lock.LockHandle;
import com.aiwork.baas.ddl.lock.ProjectDdlLockCallback;
import com.aiwork.baas.ddl.lock.ProjectDdlLockExecutor;
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

import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

    @MockitoSpyBean
    private ProjectDdlLockExecutor lockExecutor;

    @MockitoSpyBean
    private ProjectDataSourceRegistry registry;

    @Override
    protected String projectNamePrefix() {
        return "lifecycle-lock";
    }

    @Test
    void createAndRetryProvisionHoldProjectDdlLockDuringPhysicalWork() {
        AtomicInteger createOwnedSessions = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
            Connection connection = invocation.getArgument(0);
            String databaseName = invocation.getArgument(1);
            BaasProject current = projectMapper.selectOne(Wrappers.<BaasProject>lambdaQuery()
                .eq(BaasProject::getDbName, databaseName));
            assertAdvisoryOwner(connection, current);
            createOwnedSessions.incrementAndGet();
            return invocation.callRealMethod();
        }).when(provisioner).createDatabase(Mockito.any(Connection.class), Mockito.anyString());
        Mockito.doAnswer(invocation -> {
            Connection connection = invocation.getArgument(0);
            String databaseName = invocation.getArgument(3);
            BaasProject current = projectMapper.selectOne(Wrappers.<BaasProject>lambdaQuery()
                .eq(BaasProject::getDbName, databaseName));
            assertAdvisoryOwner(connection, current);
            createOwnedSessions.incrementAndGet();
            return invocation.callRealMethod();
        })
            .when(provisioner)
            .createRuntimeUser(Mockito.any(Connection.class), Mockito.anyString(), Mockito.anyString(),
                    Mockito.anyString());
        Mockito.doAnswer(invocation -> {
            Connection connection = invocation.getArgument(0);
            String databaseName = invocation.getArgument(1);
            BaasProject current = projectMapper.selectOne(Wrappers.<BaasProject>lambdaQuery()
                .eq(BaasProject::getDbName, databaseName));
            assertAdvisoryOwner(connection, current);
            createOwnedSessions.incrementAndGet();
            return invocation.callRealMethod();
        }).when(provisioner).initSystemTables(Mockito.any(Connection.class), Mockito.anyString());
        BaasProject created;
        try {
            created = lifecycleService.createProject("lk-create", 1L).project();
        }
        finally {
            Mockito.reset(provisioner);
        }
        assertThat(createOwnedSessions).hasValue(3);

        projectMapper.update(null, Wrappers.<BaasProject>lambdaUpdate()
            .eq(BaasProject::getId, created.getId())
            .set(BaasProject::getStatus, ProjectStatus.FAILED)
            .set(BaasProject::getProvisionStep, ProvisionStep.DB_CREATED.name()));
        AtomicInteger retryOwnedSessions = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
            Connection connection = invocation.getArgument(0);
            assertAdvisoryOwner(connection, created);
            retryOwnedSessions.incrementAndGet();
            return invocation.callRealMethod();
        })
            .when(provisioner)
            .createRuntimeUser(Mockito.any(Connection.class), Mockito.anyString(), Mockito.anyString(),
                    Mockito.anyString());
        Mockito.doAnswer(invocation -> {
            Connection connection = invocation.getArgument(0);
            assertAdvisoryOwner(connection, created);
            retryOwnedSessions.incrementAndGet();
            return invocation.callRealMethod();
        }).when(provisioner).initSystemTables(Mockito.any(Connection.class), Mockito.anyString());
        try {
            lifecycleService.retryProvision(created.getId());
        }
        finally {
            Mockito.reset(provisioner);
        }

        assertThat(retryOwnedSessions).hasValue(2);
        assertThat(projectMapper.selectById(created.getId()).getStatus()).isEqualTo(ProjectStatus.ACTIVE);
    }

    @Test
    void createReturnsCommittedOneTimeKeysWhenRedisOwnerChangesAfterTerminalCommit() {
        String name = "post-create-token-loss";
        String successorToken = "successor-create-token";
        AtomicBoolean ownerReplaced = replaceOwnerAfterCallback(successorToken);
        BaasProject createdProject = null;
        try {
            var created = lifecycleService.createProject(name, 1L);
            createdProject = created.project();

            assertThat(created.publishableKey()).startsWith("pub_");
            assertThat(created.secretKey()).startsWith("sec_");
            assertThat(projectMapper.selectById(createdProject.getId()).getStatus()).isEqualTo(ProjectStatus.ACTIVE);
            assertThat(ownerReplaced).isTrue();
            assertThat(redisTemplate.opsForValue().get(DdlLockManager.lockKey(createdProject.getId())))
                .isEqualTo(successorToken);
        }
        finally {
            Mockito.reset(lockExecutor);
            if (createdProject == null) {
                createdProject = projectMapper.selectOne(Wrappers.<BaasProject>lambdaQuery()
                    .eq(BaasProject::getName, name));
            }
            if (createdProject != null) {
                redisTemplate.delete(DdlLockManager.lockKey(createdProject.getId()));
            }
        }
    }

    @Test
    void retryReturnsCommittedOneTimeKeysWhenRedisOwnerChangesAfterTerminalCommit() {
        projectMapper.update(null, Wrappers.<BaasProject>lambdaUpdate()
            .eq(BaasProject::getId, project.getId())
            .set(BaasProject::getStatus, ProjectStatus.FAILED)
            .set(BaasProject::getProvisionStep, ProvisionStep.JWT_KEY.name()));
        String successorToken = "successor-retry-token";
        AtomicBoolean ownerReplaced = replaceOwnerAfterCallback(successorToken);
        try {
            var retried = lifecycleService.retryProvision(project.getId());

            assertThat(retried.publishableKey()).startsWith("pub_");
            assertThat(retried.secretKey()).startsWith("sec_");
            assertThat(projectMapper.selectById(project.getId()).getStatus()).isEqualTo(ProjectStatus.ACTIVE);
            assertThat(ownerReplaced).isTrue();
            assertThat(redisTemplate.opsForValue().get(DdlLockManager.lockKey(project.getId())))
                .isEqualTo(successorToken);
        }
        finally {
            Mockito.reset(lockExecutor);
            redisTemplate.delete(DdlLockManager.lockKey(project.getId()));
        }
    }

    @Test
    void deleteStillDrainsWhenRedisOwnerChangesAfterTerminalCommit() {
        String successorToken = "successor-delete-token";
        AtomicBoolean ownerReplaced = replaceOwnerAfterCallback(successorToken);
        Mockito.clearInvocations(registry);
        try {
            lifecycleService.deleteProject(project.getId(), 1L);

            assertThat(projectMapper.selectById(project.getId()).getStatus()).isEqualTo(ProjectStatus.DELETING);
            assertThat(ownerReplaced).isTrue();
            Mockito.verify(registry).blockAndDrain(project.getProjectRef());
            assertThat(redisTemplate.opsForValue().get(DdlLockManager.lockKey(project.getId())))
                .isEqualTo(successorToken);
        }
        finally {
            Mockito.reset(lockExecutor, registry);
            redisTemplate.delete(DdlLockManager.lockKey(project.getId()));
        }
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
            assertThat(lifecycleService.physicallyCleanup(due)).isEqualTo(ProjectCleanupResult.SKIPPED);
            assertThat(projectMapper.selectById(project.getId()).getStatus()).isEqualTo(ProjectStatus.DELETING);
            assertThat(projectMapper.selectById(project.getId()).getDdlFenceEpoch()).isEqualTo(epochBeforeCleanup);
        }
        finally {
            lockManager.release(blocker);
        }

        assertThat(lifecycleService.physicallyCleanup(due)).isEqualTo(ProjectCleanupResult.CLEANED);

        BaasProject deleted = projectMapper.selectById(project.getId());
        assertThat(deleted.getStatus()).isEqualTo(ProjectStatus.DELETED);
        assertThat(deleted.getDdlFenceEpoch()).isEqualTo(epochBeforeCleanup + 1);
    }

    @SuppressWarnings("unchecked")
    private AtomicBoolean replaceOwnerAfterCallback(String successorToken) {
        AtomicBoolean ownerReplaced = new AtomicBoolean();
        Mockito.doAnswer(invocation -> {
            Long projectId = invocation.getArgument(0);
            ProjectDdlLockCallback<Object> callback = invocation.getArgument(1);
            ProjectDdlLockCallback<Object> wrapped = (handle, connection) -> {
                Object result = callback.doInLock(handle, connection);
                String lockKey = DdlLockManager.lockKey(projectId);
                ownerReplaced.set(redisTemplate.opsForValue().get(lockKey) != null);
                redisTemplate.opsForValue().set(lockKey, successorToken);
                return result;
            };
            invocation.getRawArguments()[1] = wrapped;
            return invocation.callRealMethod();
        }).when(lockExecutor).execute(Mockito.anyLong(), Mockito.any());
        return ownerReplaced;
    }

    private void assertAdvisoryOwner(Connection connection, BaasProject current) throws Exception {
        assertThat(current).isNotNull();
        long connectionId = queryLong(connection, "SELECT CONNECTION_ID()", null);
        long lockOwner = queryLong(connection, "SELECT IS_USED_LOCK(?)",
                AdvisoryLockTemplate.lockName(current.getId()));
        assertThat(lockOwner).isEqualTo(connectionId);
        assertThat(redisTemplate.hasKey(DdlLockManager.lockKey(current.getId()))).isTrue();
    }

    private long queryLong(Connection connection, String sql, String parameter) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            if (parameter != null) {
                statement.setString(1, parameter);
            }
            try (var resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getLong(1);
            }
        }
    }

}
