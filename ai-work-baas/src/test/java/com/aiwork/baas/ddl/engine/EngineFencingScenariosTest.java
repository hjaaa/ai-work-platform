package com.aiwork.baas.ddl.engine;

import com.aiwork.baas.LifecycleTestApplication;
import com.aiwork.baas.ddl.lock.DdlLockManager;
import com.aiwork.baas.entity.BaasDdlLog;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.BaasTable;
import com.aiwork.baas.entity.BaasTableAcl;
import com.aiwork.baas.entity.enums.DdlLogStatus;
import com.aiwork.baas.entity.enums.DdlOperationType;
import com.aiwork.baas.entity.enums.DdlStep;
import com.aiwork.baas.entity.enums.ProjectStatus;
import com.aiwork.baas.entity.enums.TableStatus;
import com.aiwork.baas.exception.DdlConflictException;
import com.aiwork.baas.mapper.BaasDdlLogMapper;
import com.aiwork.baas.mapper.BaasProjectMapper;
import com.aiwork.baas.mapper.BaasTableAclMapper;
import com.aiwork.baas.mapper.BaasTableMapper;
import com.aiwork.baas.service.DdlMaintenanceJob;
import com.aiwork.baas.support.PlanBContainerSupport;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = LifecycleTestApplication.class,
        properties = { "spring.config.import=", "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false" })
class EngineFencingScenariosTest extends PlanBContainerSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private DdlExecutionEngine engine;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private DdlFencingGuard fencingGuard;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private BaasProjectMapper projectMapper;

    @Autowired
    private BaasDdlLogMapper ddlLogMapper;

    @Autowired
    private BaasTableMapper tableMapper;

    @Autowired
    private BaasTableAclMapper aclMapper;

    @Autowired
    private DdlMaintenanceJob maintenanceJob;

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

    private BaasProject newProject() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        BaasProject project = new BaasProject();
        project.setProjectRef(suffix);
        project.setName("fencing");
        project.setDbName("baas_fencing_" + suffix);
        project.setStatus(ProjectStatus.ACTIVE);
        project.setOwnerUserId(1L);
        project.setDdlFenceEpoch(0L);
        projectMapper.insert(project);
        return project;
    }

    private DdlOperationSpec spec(BaasProject project, String operationId, String hash) {
        return new DdlOperationSpec(project.getId(), operationId, DdlOperationType.CREATE, "demo", null, hash,
                null, "CREATE TABLE ...(?)");
    }

    /** §14「A 在写平台元数据前停顿」:A 暂停于 completeSuccess 前,丢 Redis 锁,B 接管完成,A 恢复被守卫拒绝。 */
    @Test
    void pausedExecutorFencedOutAfterTakeover() throws Exception {
        BaasProject project = newProject();
        String operationId = UUID.randomUUID().toString();
        String hash = "a".repeat(64);

        CountDownLatch aReachedPerform = new CountDownLatch(1);
        CountDownLatch aMayContinue = new CountDownLatch(1);
        AtomicReference<Throwable> aFailure = new AtomicReference<>();

        DdlWork pausingWork = new DdlWork() {
            @Override
            public void validateInLock(DdlWorkContext context) {
            }

            @Override
            public ObjectNode perform(DdlWorkContext context) throws Exception {
                aReachedPerform.countDown();
                aMayContinue.await(30, TimeUnit.SECONDS);
                return context.completeSuccess(() -> MAPPER.createObjectNode().put("executor", "A"));
            }
        };

        Thread executorA = new Thread(() -> {
            try {
                engine.execute(spec(project, operationId, hash), pausingWork);
            } catch (Throwable throwable) {
                aFailure.set(throwable);
            }
        }, "ddl-fencing-executor-a");
        executorA.start();
        assertThat(aReachedPerform.await(30, TimeUnit.SECONDS)).isTrue();

        // 模拟 A 的 Redis 租约过期(watchdog 停顿):直接删 key;A 的 advisory lock 仍被持有,
        // 因此 B 必须等 A 的连接结束——先放行 A 让其撞守卫失败,advisory 随之释放
        redisTemplate.delete(DdlLockManager.lockKey(project.getId()));
        aMayContinue.countDown();
        executorA.join(30000);
        assertThat(aFailure.get()).isInstanceOf(StaleExecutorException.class);

        // 日志仍为 RUNNING(A 的终态写入被拒),B 以同 ID 接管续跑成功
        BaasDdlLog leftover = ddlLogMapper.selectByProjectAndOperation(project.getId(), operationId);
        assertThat(leftover.getStatus()).isEqualTo(DdlLogStatus.RUNNING.name());
        TestWorks.RecordingWork workB = new TestWorks.RecordingWork();
        ObjectNode snapshot = engine.execute(spec(project, operationId, hash), workB);
        assertThat(workB.observedBranch).isEqualTo(OwnershipBranch.TAKE_OVER_RUNNING);
        assertThat(snapshot.get("performs").asInt()).isEqualTo(1);
    }

    /** §14「跨 operationId 项目级 epoch fencing」——reconcile-vs-ALTER 形态。 */
    @Test
    void staleExecutorAcrossOperationIdsRolledBackByProjectEpoch() {
        BaasProject project = newProject();
        // A:手工建立所有权(epoch=1 的 RUNNING 日志,模拟已丢双锁的旧执行者)
        transactionTemplate.executeWithoutResult(status -> fencingGuard.incrementEpochInTx(project.getId()));
        BaasDdlLog logA = new BaasDdlLog();
        logA.setProjectId(project.getId());
        logA.setOperationId(UUID.randomUUID().toString());
        logA.setOperationType(DdlOperationType.RECONCILE.code());
        logA.setRequestHash("r".repeat(64));
        logA.setStep(DdlStep.PREPARED.name());
        logA.setStatus(DdlLogStatus.RUNNING.name());
        logA.setOwnerToken("executor-a");
        logA.setFenceEpoch(1L);
        logA.setRetryCount(0);
        ddlLogMapper.insert(logA);

        // B:不同 operationId 的完整引擎执行(epoch → 2)
        engine.execute(spec(project, UUID.randomUUID().toString(), "b".repeat(64)), new TestWorks.RecordingWork());
        assertThat(projectMapper.selectById(project.getId()).getDdlFenceEpoch()).isEqualTo(2L);

        // A 恢复:owner_token 守卫恒过(自己的日志行),但项目 epoch 不匹配 → 整笔回滚
        BaasTable ghost = new BaasTable();
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            ghost.setProjectId(project.getId());
            ghost.setTableName("stale_write");
            ghost.setStatus(TableStatus.ACTIVE.name());
            tableMapper.insert(ghost);
            fencingGuard.verifyEpochInTx(project.getId(), 1L);
        })).isInstanceOf(StaleExecutorException.class);
        assertThat(tableMapper.selectCount(Wrappers.<BaasTable>lambdaQuery()
                .eq(BaasTable::getProjectId, project.getId())
                .eq(BaasTable::getTableName, "stale_write"))).isZero();
    }

    /** §14「ACL 关闭-vs-开启」形态:陈旧的 ACL 关闭事务不得覆盖新完成的开启。 */
    @Test
    void staleAclCloseRolledBackKeepsNewerOpen() {
        BaasProject project = newProject();
        BaasTable table = new BaasTable();
        table.setProjectId(project.getId());
        table.setTableName("acl_fence");
        table.setStatus(TableStatus.ACTIVE.name());
        tableMapper.insert(table);
        BaasTableAcl acl = new BaasTableAcl();
        acl.setTableId(table.getId());
        acl.setRole("anon");
        acl.setCanSelect(true);
        acl.setCanInsert(false);
        acl.setCanUpdate(false);
        acl.setCanDelete(false);
        aclMapper.insert(acl);
        // 新执行者已把 epoch 推到 1
        transactionTemplate.executeWithoutResult(status -> fencingGuard.incrementEpochInTx(project.getId()));

        // 陈旧执行者(持有 epoch=0)试图关闭 ACL → 整笔回滚
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            aclMapper.update(null, Wrappers.<BaasTableAcl>lambdaUpdate()
                    .eq(BaasTableAcl::getId, acl.getId())
                    .set(BaasTableAcl::getCanSelect, false));
            fencingGuard.verifyEpochInTx(project.getId(), 0L);
        })).isInstanceOf(StaleExecutorException.class);
        assertThat(aclMapper.selectById(acl.getId()).getCanSelect()).isTrue();
    }

    /** §14「并发 FAILED 重试仅一个执行者成功」:Redis 锁互斥保证单赢家,输家 409。 */
    @Test
    void concurrentFailedRetrySingleWinner() throws Exception {
        BaasProject project = newProject();
        String operationId = UUID.randomUUID().toString();
        String hash = "a".repeat(64);
        TestWorks.RecordingWork failing = new TestWorks.RecordingWork();
        failing.failPerform.set(true);
        assertThatThrownBy(() -> engine.execute(spec(project, operationId, hash), failing))
                .isInstanceOf(IllegalStateException.class);

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();
        AtomicReference<Throwable> unexpectedFailure = new AtomicReference<>();
        Runnable retry = () -> {
            try {
                start.await(10, TimeUnit.SECONDS);
                TestWorks.RecordingWork work = new TestWorks.RecordingWork();
                work.pauseInPerformMillis = 300;
                engine.execute(spec(project, operationId, hash), work);
                successCount.incrementAndGet();
            } catch (DdlConflictException conflict) {
                conflictCount.incrementAndGet();
            } catch (Throwable throwable) {
                unexpectedFailure.compareAndSet(null, throwable);
            }
        };
        Thread first = new Thread(retry, "ddl-failed-retry-first");
        Thread second = new Thread(retry, "ddl-failed-retry-second");
        first.start();
        second.start();
        start.countDown();
        first.join(30000);
        second.join(30000);

        assertThat(unexpectedFailure.get()).isNull();
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);
        assertThat(ddlLogMapper.selectByProjectAndOperation(project.getId(), operationId).getRetryCount())
                .isEqualTo(1);
    }

    /** §14「cleanup DROP 已执行而终态提交前崩溃」:DDL_APPLIED 断点接管续跑,只补元数据。 */
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
        cleanup.setFenceEpoch(0L);
        cleanup.setRetryCount(0);
        ddlLogMapper.insert(cleanup);

        maintenanceJob.scanOnce();

        assertThat(ddlLogMapper.selectById(cleanup.getId()).getStatus()).isEqualTo(DdlLogStatus.SUCCESS.name());
        assertThat(tableMapper.selectById(table.getId())).isNull();
    }

}
