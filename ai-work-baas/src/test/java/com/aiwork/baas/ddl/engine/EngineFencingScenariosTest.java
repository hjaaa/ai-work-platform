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

package com.aiwork.baas.ddl.engine;

import com.aiwork.baas.LifecycleTestApplication;
import com.aiwork.baas.controller.dto.AclConfigDTO;
import com.aiwork.baas.controller.dto.AclPutDTO;
import com.aiwork.baas.controller.dto.AclRoleDTO;
import com.aiwork.baas.controller.dto.ColumnDefinitionDTO;
import com.aiwork.baas.controller.dto.ReconcileTriggerDTO;
import com.aiwork.baas.controller.dto.TableAlterDTO;
import com.aiwork.baas.controller.dto.TableCreateDTO;
import com.aiwork.baas.ddl.lock.AdvisoryLockTemplate;
import com.aiwork.baas.ddl.lock.DdlLockManager;
import com.aiwork.baas.entity.BaasAuditLog;
import com.aiwork.baas.entity.BaasDdlLog;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.BaasTable;
import com.aiwork.baas.entity.enums.DdlLogStatus;
import com.aiwork.baas.entity.enums.DdlOperationType;
import com.aiwork.baas.entity.enums.DdlStep;
import com.aiwork.baas.entity.enums.TableStatus;
import com.aiwork.baas.exception.DdlConflictException;
import com.aiwork.baas.mapper.BaasAuditLogMapper;
import com.aiwork.baas.mapper.BaasDdlLogMapper;
import com.aiwork.baas.mapper.BaasTableMapper;
import com.aiwork.baas.service.AclConfigService;
import com.aiwork.baas.service.DdlMaintenanceJob;
import com.aiwork.baas.service.ProjectLifecycleService;
import com.aiwork.baas.service.ReconcileService;
import com.aiwork.baas.service.TableManagementService;
import com.aiwork.baas.support.PlanBContainerSupport;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@SpringBootTest(classes = LifecycleTestApplication.class,
        properties = { "spring.config.import=", "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false" })
class EngineFencingScenariosTest extends PlanBContainerSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final AclRoleDTO ALL_OFF = new AclRoleDTO(false, false, false, false);

    @MockitoSpyBean
    private DdlExecutionEngine engine;

    @MockitoSpyBean
    private DdlFencingGuard fencingGuard;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private DdlLockManager lockManager;

    @Autowired
    private ProjectLifecycleService lifecycleService;

    @Autowired
    private TableManagementService tableService;

    @Autowired
    private ReconcileService reconcileService;

    @Autowired
    private AclConfigService aclService;

    @Autowired
    private BaasDdlLogMapper ddlLogMapper;

    @Autowired
    private BaasTableMapper tableMapper;

    @Autowired
    private BaasAuditLogMapper auditLogMapper;

    @Autowired
    private DdlMaintenanceJob maintenanceJob;

    private JdbcTemplate rootJdbc;

    private CompletionPause activePause;

    private final ThreadLocal<DdlWorkContext> completingContext = new ThreadLocal<>();

    @BeforeEach
    void prepareJdbc() {
        rootJdbc = new JdbcTemplate(mysqlDataSource());
    }

    @AfterEach
    void releasePauseAndResetSpy() {
        if (activePause != null) {
            activePause.resume();
            activePause = null;
        }
        completingContext.remove();
        reset(engine, fencingGuard);
    }

    @DynamicPropertySource
    static void registerPlanBProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", () -> MYSQL_USERNAME);
        registry.add("spring.datasource.password", () -> MYSQL_PASSWORD);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
        registry.add("baas.provisioner.url", () -> MYSQL.getJdbcUrl().replace("/ai_work_baas", "/mysql"));
        registry.add("baas.provisioner.username", () -> MYSQL_USERNAME);
        registry.add("baas.provisioner.password", () -> MYSQL_PASSWORD);
        registry.add("baas.project-db.host", MYSQL::getHost);
        registry.add("baas.project-db.port", () -> MYSQL.getMappedPort(3306));
        registry.add("server.servlet.context-path", () -> "");
    }

    /** A 通过锁校验、进入 epoch 校验前失去双锁并暂停；B 真接管完成后，A 的迟到写均被拒绝。 */
    @Test
    void pausedExecutorFencedOutAfterSuccessorCompletes() throws Exception {
        BaasProject project = newProject();
        String operationId = UUID.randomUUID().toString();
        String hash = "a".repeat(64);
        CompletionPause pause = pauseBeforeEpochVerification(operationId);
        AtomicInteger metadataWriteCount = new AtomicInteger();
        AtomicReference<Throwable> checkpointFailure = new AtomicReference<>();
        AtomicReference<Throwable> terminalFailure = new AtomicReference<>();
        AtomicReference<Throwable> executorFailure = new AtomicReference<>();

        DdlWork staleWork = new DdlWork() {
            @Override
            public void validateInLock(DdlWorkContext context) {
            }

            @Override
            public ObjectNode perform(DdlWorkContext context) {
                StaleExecutorException firstFailure;
                try {
                    context.completeSuccess(() -> {
                        metadataWriteCount.incrementAndGet();
                        return MAPPER.createObjectNode().put("executor", "A");
                    });
                    throw new AssertionError("陈旧执行者的首次完成不应成功");
                }
                catch (StaleExecutorException stale) {
                    firstFailure = stale;
                }
                try {
                    context.advanceToDdlApplied();
                }
                catch (Throwable throwable) {
                    checkpointFailure.set(throwable);
                }
                try {
                    context.completeSuccess(() -> {
                        metadataWriteCount.incrementAndGet();
                        return MAPPER.createObjectNode().put("executor", "A-late");
                    });
                }
                catch (Throwable throwable) {
                    terminalFailure.set(throwable);
                }
                throw firstFailure;
            }
        };

        Thread executorA = startThread("ddl-fencing-executor-a",
                () -> engine.execute(spec(project, operationId, hash), staleWork), executorFailure);
        pause.awaitPaused();

        TestWorks.RecordingWork successorWork = new TestWorks.RecordingWork();
        ObjectNode successorSnapshot;
        try {
            successorSnapshot = engine.execute(spec(project, operationId, hash), successorWork);
            assertThat(executorA.isAlive()).isTrue();
        }
        finally {
            pause.resume();
            executorA.join(30000);
        }

        assertThat(executorA.isAlive()).isFalse();
        pause.assertHealthy();
        pause.assertReachedEpochVerification();
        assertThat(successorWork.observedBranch).isEqualTo(OwnershipBranch.TAKE_OVER_RUNNING);
        assertThat(successorSnapshot.get("performs").asInt()).isEqualTo(1);
        assertThat(executorFailure.get()).isInstanceOf(StaleExecutorException.class)
            .hasMessageContaining("项目 epoch 已推进");
        assertThat(checkpointFailure.get()).isInstanceOf(StaleExecutorException.class);
        assertThat(terminalFailure.get()).isInstanceOf(StaleExecutorException.class);
        assertThat(metadataWriteCount.get()).as("epoch 不匹配时不得执行 A 的 metadata supplier").isZero();
        BaasDdlLog completed = ddlLogMapper.selectByProjectAndOperation(project.getId(), operationId);
        assertThat(completed.getStatus()).isEqualTo(DdlLogStatus.SUCCESS.name());
        assertThat(completed.getStep()).isEqualTo(DdlStep.METADATA_APPLIED.name());
        assertThat(completed.getFenceEpoch()).isEqualTo(2L);
        assertThat(completed.getResultSnapshot()).as("A 的 finishGuarded 不得覆盖 B 的终态快照")
            .contains("\"performs\":1")
            .doesNotContain("executor");
    }

    /**
     * 旧 reconcile 捕获原物理快照后暂停；新 ALTER 完成后，旧快照不得回写覆盖新列元数据。
     */
    @Test
    void staleReconcileCannotOverwriteNewerAlter() throws Exception {
        BaasProject project = newProject();
        tableService.createTable(project, new TableCreateDTO(UUID.randomUUID().toString(), "reconcile_fence",
                null, List.of(new ColumnDefinitionDTO("title", "varchar", 32, null, true, null, false, false,
                        null))));
        String reconcileOperationId = UUID.randomUUID().toString();
        String alterOperationId = UUID.randomUUID().toString();
        CompletionPause pause = pauseBeforeEpochVerification(reconcileOperationId);
        AtomicReference<Throwable> reconcileFailure = new AtomicReference<>();
        Thread staleReconcile = startThread("ddl-stale-reconcile",
                () -> reconcileService.manualReconcile(project, new ReconcileTriggerDTO(reconcileOperationId)),
                reconcileFailure);
        pause.awaitPaused();

        try {
            tableService.alterTable(project, "reconcile_fence",
                    new TableAlterDTO(alterOperationId, null, null, "newer-alter", null, null,
                            List.of(new ColumnDefinitionDTO("title", "varchar", 64, null, true, null, false,
                                    false, null)), null));
            assertColumnLength(project, "reconcile_fence", "title", 64);
            assertThat(staleReconcile.isAlive()).isTrue();
        }
        finally {
            pause.resume();
            staleReconcile.join(30000);
        }

        assertThat(staleReconcile.isAlive()).isFalse();
        pause.assertHealthy();
        pause.assertReachedEpochVerification();
        assertThat(reconcileFailure.get()).isInstanceOf(StaleExecutorException.class)
            .hasMessageContaining("项目 epoch 已推进");
        assertColumnLength(project, "reconcile_fence", "title", 64);
        ObjectNode snapshot = tableService.getTableSnapshot(project, "reconcile_fence");
        assertThat(snapshot.get("comment").asText()).isEqualTo("newer-alter");
        assertThat(snapshot.get("status").asText()).isEqualTo(TableStatus.ACTIVE.name());
        assertThat(ddlLogMapper.selectByProjectAndOperation(project.getId(), reconcileOperationId).getStatus())
                .isEqualTo(DdlLogStatus.RUNNING.name());
        assertThat(ddlLogMapper.selectByProjectAndOperation(project.getId(), alterOperationId).getStatus())
                .isEqualTo(DdlLogStatus.SUCCESS.name());
        assertThat(auditLogMapper.selectCount(Wrappers.<BaasAuditLog>lambdaQuery()
                .eq(BaasAuditLog::getProjectId, project.getId())
                .eq(BaasAuditLog::getAction, "DDL_RECONCILE")
                .like(BaasAuditLog::getDetail, reconcileOperationId))).isZero();
    }

    /** 旧 ACL 关闭在 epoch 校验前暂停；新 ACL 开启完成后，旧事务不得反向覆盖。 */
    @Test
    void staleAclCloseCannotOverwriteNewerOpen() throws Exception {
        BaasProject project = newProject();
        tableService.createTable(project, new TableCreateDTO(UUID.randomUUID().toString(), "acl_fence", null,
                List.of(new ColumnDefinitionDTO("title", "varchar", 64, null, true, null, false, false, null))));
        AclRoleDTO initial = new AclRoleDTO(true, false, false, false);
        aclService.putAcl(project, "acl_fence", aclPut(UUID.randomUUID().toString(), initial, initial));
        String closeOperationId = UUID.randomUUID().toString();
        String openOperationId = UUID.randomUUID().toString();
        CompletionPause pause = pauseBeforeEpochVerification(closeOperationId);
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Thread staleClose = startThread("ddl-stale-acl-close",
                () -> aclService.putAcl(project, "acl_fence", aclPut(closeOperationId, ALL_OFF, ALL_OFF)),
                closeFailure);
        pause.awaitPaused();

        AclRoleDTO anonOpen = new AclRoleDTO(true, true, false, false);
        AclRoleDTO authenticatedOpen = new AclRoleDTO(true, false, true, true);
        try {
            aclService.putAcl(project, "acl_fence", aclPut(openOperationId, anonOpen, authenticatedOpen));
            assertThat(staleClose.isAlive()).isTrue();
        }
        finally {
            pause.resume();
            staleClose.join(30000);
        }

        assertThat(staleClose.isAlive()).isFalse();
        pause.assertHealthy();
        pause.assertReachedEpochVerification();
        assertThat(closeFailure.get()).isInstanceOf(StaleExecutorException.class)
            .hasMessageContaining("项目 epoch 已推进");
        ObjectNode acl = aclService.getAcl(project, "acl_fence");
        assertThat(acl.at("/acl/anon/select").asBoolean()).isTrue();
        assertThat(acl.at("/acl/anon/insert").asBoolean()).isTrue();
        assertThat(acl.at("/acl/anon/update").asBoolean()).isFalse();
        assertThat(acl.at("/acl/anon/delete").asBoolean()).isFalse();
        assertThat(acl.at("/acl/authenticated/select").asBoolean()).isTrue();
        assertThat(acl.at("/acl/authenticated/insert").asBoolean()).isFalse();
        assertThat(acl.at("/acl/authenticated/update").asBoolean()).isTrue();
        assertThat(acl.at("/acl/authenticated/delete").asBoolean()).isTrue();
        assertThat(acl.get("ownerColumn").isNull()).isTrue();
        assertThat(ddlLogMapper.selectByProjectAndOperation(project.getId(), closeOperationId).getStatus())
                .isEqualTo(DdlLogStatus.RUNNING.name());
        assertThat(ddlLogMapper.selectByProjectAndOperation(project.getId(), openOperationId).getStatus())
                .isEqualTo(DdlLogStatus.SUCCESS.name());
    }

    /** FAILED 重试：赢家在 perform 内等待输家拿到 409，整个竞争仅由 latch 排序。 */
    @Test
    void concurrentFailedRetrySingleWinnerWithoutTimingSleep() throws Exception {
        BaasProject project = newProject();
        String operationId = UUID.randomUUID().toString();
        String hash = "b".repeat(64);
        TestWorks.RecordingWork failing = new TestWorks.RecordingWork();
        failing.failPerform.set(true);
        assertThatThrownBy(() -> engine.execute(spec(project, operationId, hash), failing))
                .isInstanceOf(IllegalStateException.class);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch startWinner = new CountDownLatch(1);
        CountDownLatch winnerInPerform = new CountDownLatch(1);
        CountDownLatch loserConflict = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();
        AtomicReference<OwnershipBranch> winnerBranch = new AtomicReference<>();
        AtomicReference<Throwable> winnerFailure = new AtomicReference<>();
        AtomicReference<Throwable> loserFailure = new AtomicReference<>();

        DdlWork winnerWork = new DdlWork() {
            @Override
            public void validateInLock(DdlWorkContext context) {
                winnerBranch.set(context.branch());
            }

            @Override
            public ObjectNode perform(DdlWorkContext context) throws Exception {
                winnerInPerform.countDown();
                if (!loserConflict.await(30, TimeUnit.SECONDS)) {
                    throw new AssertionError("输家未在赢家完成前拿到 409");
                }
                context.advanceToDdlApplied();
                return context.completeSuccess(() -> MAPPER.createObjectNode().put("winner", true));
            }
        };

        Thread winner = new Thread(() -> {
            ready.countDown();
            try {
                startWinner.await(30, TimeUnit.SECONDS);
                engine.execute(spec(project, operationId, hash), winnerWork);
                successCount.incrementAndGet();
            }
            catch (Throwable throwable) {
                winnerFailure.set(throwable);
            }
        }, "ddl-failed-retry-winner");
        Thread loser = new Thread(() -> {
            ready.countDown();
            try {
                if (!winnerInPerform.await(30, TimeUnit.SECONDS)) {
                    throw new AssertionError("赢家未进入 perform");
                }
                engine.execute(spec(project, operationId, hash), new TestWorks.RecordingWork());
            }
            catch (DdlConflictException conflict) {
                conflictCount.incrementAndGet();
                loserConflict.countDown();
            }
            catch (Throwable throwable) {
                loserFailure.set(throwable);
                loserConflict.countDown();
            }
        }, "ddl-failed-retry-loser");
        winner.start();
        loser.start();
        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
        startWinner.countDown();
        winner.join(30000);
        loser.join(30000);

        assertThat(winner.isAlive()).isFalse();
        assertThat(loser.isAlive()).isFalse();
        assertThat(winnerFailure.get()).isNull();
        assertThat(loserFailure.get()).isNull();
        assertThat(winnerBranch.get()).isEqualTo(OwnershipBranch.RETRY_FAILED);
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);
        assertThat(ddlLogMapper.selectByProjectAndOperation(project.getId(), operationId).getRetryCount())
                .isEqualTo(1);
    }

    /** cleanup DROP 已执行而终态提交前崩溃：DDL_APPLIED 断点接管续跑，只补元数据。 */
    @Test
    void cleanupResumesFromDdlAppliedCheckpoint() {
        BaasProject project = newProject();
        BaasTable table = new BaasTable();
        table.setProjectId(project.getId());
        table.setTableName("half_dropped");
        table.setStatus(TableStatus.DELETED.name());
        table.setDeleteAfter(java.time.LocalDateTime.now().minusDays(1));
        tableMapper.insert(table);
        BaasDdlLog cleanup = new BaasDdlLog();
        cleanup.setProjectId(project.getId());
        cleanup.setOperationId(UUID.randomUUID().toString());
        cleanup.setOperationType(DdlOperationType.CLEANUP_DROP.code());
        cleanup.setTableName("half_dropped");
        cleanup.setTableId(table.getId());
        cleanup.setRequestHash("c".repeat(64));
        cleanup.setStep(DdlStep.DDL_APPLIED.name());
        cleanup.setStatus(DdlLogStatus.RUNNING.name());
        cleanup.setOwnerToken("dead");
        cleanup.setFenceEpoch(project.getDdlFenceEpoch());
        cleanup.setRetryCount(0);
        ddlLogMapper.insert(cleanup);

        maintenanceJob.scanOnce();

        assertThat(ddlLogMapper.selectById(cleanup.getId()).getStatus()).isEqualTo(DdlLogStatus.SUCCESS.name());
        assertThat(tableMapper.selectById(table.getId())).isNull();
    }

    private BaasProject newProject() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return lifecycleService.createProject("fencing-" + suffix, 1L).project();
    }

    private DdlOperationSpec spec(BaasProject project, String operationId, String hash) {
        return new DdlOperationSpec(project.getId(), operationId, DdlOperationType.CREATE, "demo", null, hash,
                null, "CREATE TABLE ...(?)");
    }

    private AclPutDTO aclPut(String operationId, AclRoleDTO anon, AclRoleDTO authenticated) {
        return new AclPutDTO(operationId, new AclConfigDTO(anon, authenticated), null);
    }

    private void assertColumnLength(BaasProject project, String tableName, String columnName, int expected) {
        Long physicalLength = rootJdbc.queryForObject("SELECT CHARACTER_MAXIMUM_LENGTH "
                + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Long.class, project.getDbName(), tableName, columnName);
        assertThat(physicalLength).isEqualTo((long) expected);
        JsonNode metadataColumn = tableService.getTableSnapshot(project, tableName)
            .withArray("columns")
            .findParents("columnName")
            .stream()
            .filter(node -> columnName.equals(node.get("columnName").asText()))
            .findFirst()
            .orElseThrow();
        assertThat(metadataColumn.get("length").asInt()).isEqualTo(expected);
    }

    private Thread startThread(String name, Runnable task, AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> {
            try {
                task.run();
            }
            catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, name);
        thread.start();
        return thread;
    }

    private CompletionPause pauseBeforeEpochVerification(String operationId) {
        CompletionPause pause = new CompletionPause(operationId);
        activePause = pause;
        doAnswer(invocation -> {
            DdlWorkContext context = invocation.getArgument(0);
            completingContext.set(context);
            try {
                return invocation.callRealMethod();
            }
            finally {
                completingContext.remove();
            }
        }).when(engine).completeSuccess(any(), any());
        doAnswer(invocation -> {
            DdlWorkContext context = completingContext.get();
            if (context != null && pause.matches(context)) {
                context.assertLockStillHeld();
                pause.reachedEpochVerification.set(true);
                releaseOwnershipAndPause(context, pause);
            }
            return invocation.callRealMethod();
        }).when(fencingGuard).verifyEpochInTx(any(), anyLong());
        return pause;
    }

    private void releaseOwnershipAndPause(DdlWorkContext context, CompletionPause pause) throws Throwable {
        try {
            String lockKey = DdlLockManager.lockKey(context.spec().projectId());
            String currentOwner = redisTemplate.opsForValue().get(lockKey);
            if (!context.ownerToken().equals(currentOwner)) {
                throw new AssertionError("暂停前 Redis owner_token 不属于 A");
            }
            Integer advisoryReleased = context.projectJdbc().queryForObject("SELECT RELEASE_LOCK(?)", Integer.class,
                    AdvisoryLockTemplate.lockName(context.spec().projectId()));
            if (!Integer.valueOf(1).equals(advisoryReleased)) {
                throw new AssertionError("A 未真实持有 advisory lock");
            }
            lockManager.release(context.lockHandle());
            if (redisTemplate.opsForValue().get(lockKey) != null) {
                throw new AssertionError("A 的 Redis owner_token 未真实释放");
            }
        }
        catch (Throwable throwable) {
            pause.failure.set(throwable);
            pause.reached.countDown();
            throw throwable;
        }
        pause.reached.countDown();
        if (!pause.resume.await(30, TimeUnit.SECONDS)) {
            AssertionError timeout = new AssertionError("A 在 epoch 校验入口等待 B 超时");
            pause.failure.set(timeout);
            throw timeout;
        }
    }

    private static final class CompletionPause {

        private final String operationId;

        private final AtomicBoolean matched = new AtomicBoolean();

        private final CountDownLatch reached = new CountDownLatch(1);

        private final CountDownLatch resume = new CountDownLatch(1);

        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private final AtomicBoolean reachedEpochVerification = new AtomicBoolean();

        private CompletionPause(String operationId) {
            this.operationId = operationId;
        }

        private boolean matches(DdlWorkContext context) {
            return operationId.equals(context.spec().operationId()) && matched.compareAndSet(false, true);
        }

        private void awaitPaused() throws InterruptedException {
            assertThat(reached.await(30, TimeUnit.SECONDS)).isTrue();
            assertHealthy();
        }

        private void resume() {
            resume.countDown();
        }

        private void assertHealthy() {
            assertThat(failure.get()).isNull();
        }

        private void assertReachedEpochVerification() {
            assertThat(reachedEpochVerification.get()).isTrue();
        }

    }

}
