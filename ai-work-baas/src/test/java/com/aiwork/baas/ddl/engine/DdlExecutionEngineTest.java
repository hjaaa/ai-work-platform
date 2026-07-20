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

import com.aiwork.baas.LifecycleTestApplication;
import com.aiwork.baas.ddl.lock.DdlLockManager;
import com.aiwork.baas.ddl.lock.LockHandle;
import com.aiwork.baas.entity.BaasDdlLog;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.enums.DdlLogStatus;
import com.aiwork.baas.entity.enums.DdlOperationType;
import com.aiwork.baas.entity.enums.DdlStep;
import com.aiwork.baas.entity.enums.ProjectStatus;
import com.aiwork.baas.exception.DdlConflictException;
import com.aiwork.baas.exception.DdlExecutionException;
import com.aiwork.baas.mapper.BaasDdlLogMapper;
import com.aiwork.baas.mapper.BaasProjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

@SpringBootTest(classes = LifecycleTestApplication.class, properties = { "spring.config.import=",
        "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false" })
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class DdlExecutionEngineTest {

    @Container
    static MySQLContainer mysql = new MySQLContainer("mysql:8.4").withUsername("root")
        .withPassword("root")
        .withDatabaseName("ai_work_baas")
        .withInitScript("init-metadata.sql");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "root");
        registry.add("baas.provisioner.url", () -> mysql.getJdbcUrl().replace("/ai_work_baas", "/mysql"));
        registry.add("baas.provisioner.username", () -> "root");
        registry.add("baas.provisioner.password", () -> "root");
        registry.add("baas.project-db.host", mysql::getHost);
        registry.add("baas.project-db.port", () -> mysql.getMappedPort(3306));
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("server.servlet.context-path", () -> "");
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private DdlExecutionEngine engine;

    @Autowired
    private DdlLockManager lockManager;

    @Autowired
    private BaasProjectMapper projectMapper;

    @Autowired
    private BaasDdlLogMapper ddlLogMapper;

    private BaasProject newProject() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        BaasProject project = new BaasProject();
        project.setProjectRef(suffix);
        project.setName("engine");
        project.setDbName("baas_engine_" + suffix);
        project.setStatus(ProjectStatus.ACTIVE);
        project.setOwnerUserId(1L);
        projectMapper.insert(project);
        return project;
    }

    private DdlOperationSpec spec(BaasProject project, String operationId, String hash) {
        return new DdlOperationSpec(project.getId(), operationId, DdlOperationType.CREATE, "demo", null, hash,
                null, "CREATE TABLE ...(?)");
    }

    @Test
    void newOperationSucceedsWithEpochAndSnapshot() {
        BaasProject project = newProject();
        String operationId = UUID.randomUUID().toString();
        TestWorks.RecordingWork work = new TestWorks.RecordingWork();

        ObjectNode snapshot = engine.execute(spec(project, operationId, "a".repeat(64)), work);

        assertThat(snapshot.get("performs").asInt()).isEqualTo(1);
        assertThat(work.observedBranch).isEqualTo(OwnershipBranch.NEW_OPERATION);
        BaasDdlLog logRecord = ddlLogMapper.selectByProjectAndOperation(project.getId(), operationId);
        assertThat(logRecord.getStatus()).isEqualTo(DdlLogStatus.SUCCESS.name());
        assertThat(logRecord.getStep()).isEqualTo(DdlStep.METADATA_APPLIED.name());
        assertThat(logRecord.getFenceEpoch()).isEqualTo(1L);
        assertThat(projectMapper.selectById(project.getId()).getDdlFenceEpoch()).isEqualTo(1L);
    }

    @Test
    void resolvedOperationSpecIsComputedOnlyAfterProjectLockIsHeld() {
        BaasProject project = newProject();
        String operationId = UUID.randomUUID().toString();
        AtomicBoolean resolvedUnderLock = new AtomicBoolean(false);
        TestWorks.RecordingWork work = new TestWorks.RecordingWork();

        ObjectNode snapshot = engine.executeResolved(project.getId(), () -> {
            LockHandle competing = lockManager.tryAcquire(project.getId());
            resolvedUnderLock.set(competing == null);
            if (competing != null) {
                lockManager.release(competing);
            }
            return spec(project, operationId, "a".repeat(64));
        }, ignored -> work);

        assertThat(resolvedUnderLock).isTrue();
        assertThat(snapshot.get("performs").asInt()).isEqualTo(1);
        assertThat(work.observedBranch).isEqualTo(OwnershipBranch.NEW_OPERATION);
    }

    @Test
    void successReplayReturnsSnapshotWithoutTakingLock() {
        BaasProject project = newProject();
        String operationId = UUID.randomUUID().toString();
        TestWorks.RecordingWork work = new TestWorks.RecordingWork();
        engine.execute(spec(project, operationId, "a".repeat(64)), work);

        LockHandle blocker = lockManager.tryAcquire(project.getId());
        assertThat(blocker).isNotNull();
        try {
            ObjectNode replay = engine.execute(spec(project, operationId, "a".repeat(64)),
                    new TestWorks.RecordingWork());
            assertThat(replay.get("performs").asInt()).isEqualTo(1);
            assertThat(work.performCount.get()).isEqualTo(1);
        }
        finally {
            lockManager.release(blocker);
        }
    }

    @Test
    void corruptSuccessSnapshotDoesNotExposePersistedContentAsExceptionCause() {
        BaasProject project = newProject();
        String operationId = UUID.randomUUID().toString();
        engine.execute(spec(project, operationId, "a".repeat(64)), new TestWorks.RecordingWork());
        BaasDdlLog logRecord = ddlLogMapper.selectByProjectAndOperation(project.getId(), operationId);
        logRecord.setResultSnapshot("{secret_table:secret_value}");
        ddlLogMapper.updateById(logRecord);

        assertThatThrownBy(() -> engine.execute(spec(project, operationId, "a".repeat(64)),
                new TestWorks.RecordingWork()))
            .isInstanceOf(com.aiwork.baas.exception.DdlExecutionException.class)
            .hasMessage("DDL 执行失败")
            .hasNoCause();
    }

    @Test
    void fingerprintMismatchRejected() {
        BaasProject project = newProject();
        String operationId = UUID.randomUUID().toString();
        engine.execute(spec(project, operationId, "a".repeat(64)), new TestWorks.RecordingWork());

        assertThatThrownBy(() -> engine.execute(spec(project, operationId, "b".repeat(64)),
                new TestWorks.RecordingWork()))
            .isInstanceOf(DdlConflictException.class);
    }

    @Test
    void lockHeldByOtherRejectedWith409Semantics() {
        BaasProject project = newProject();
        LockHandle blocker = lockManager.tryAcquire(project.getId());
        try {
            assertThatThrownBy(() -> engine.execute(spec(project, UUID.randomUUID().toString(), "a".repeat(64)),
                    new TestWorks.RecordingWork()))
                .isInstanceOf(DdlConflictException.class);
        }
        finally {
            lockManager.release(blocker);
        }
    }

    @Test
    void validationFailureLeavesNoLogAndNoEpochChange() {
        BaasProject project = newProject();
        String operationId = UUID.randomUUID().toString();
        DdlWork work = new DdlWork() {
            @Override
            public void validateInLock(DdlWorkContext context) {
                throw new com.aiwork.baas.exception.BaasBadRequestException("bad request in lock");
            }

            @Override
            public ObjectNode perform(DdlWorkContext context) {
                throw new IllegalStateException("unreachable");
            }
        };

        assertThatThrownBy(() -> engine.execute(spec(project, operationId, "a".repeat(64)), work))
            .isInstanceOf(com.aiwork.baas.exception.BaasBadRequestException.class);
        assertThat(ddlLogMapper.selectByProjectAndOperation(project.getId(), operationId)).isNull();
        assertThat(projectMapper.selectById(project.getId()).getDdlFenceEpoch()).isZero();
    }

    @Test
    void ownershipTxFailureRevertsEpochAndLeavesNoLog() {
        BaasProject project = newProject();
        String operationId = UUID.randomUUID().toString();
        TestWorks.RecordingWork work = new TestWorks.RecordingWork();
        work.failOwnershipTx.set(true);

        assertThatThrownBy(() -> engine.execute(spec(project, operationId, "a".repeat(64)), work))
            .isInstanceOf(IllegalStateException.class);
        assertThat(ddlLogMapper.selectByProjectAndOperation(project.getId(), operationId)).isNull();
        assertThat(projectMapper.selectById(project.getId()).getDdlFenceEpoch()).isZero();
        assertThat(work.performCount.get()).isZero();
    }

    @Test
    void performFailureMarksFailedThenRetryTakesFailedBranch() {
        BaasProject project = newProject();
        String operationId = UUID.randomUUID().toString();
        TestWorks.RecordingWork work = new TestWorks.RecordingWork();
        work.failPerform.set(true);

        assertThatThrownBy(() -> engine.execute(spec(project, operationId, "a".repeat(64)), work))
            .isInstanceOf(IllegalStateException.class);
        BaasDdlLog failed = ddlLogMapper.selectByProjectAndOperation(project.getId(), operationId);
        assertThat(failed.getStatus()).isEqualTo(DdlLogStatus.FAILED.name());
        assertThat(work.failureTxCalled).isTrue();

        work.failPerform.set(false);
        ObjectNode snapshot = engine.execute(spec(project, operationId, "a".repeat(64)), work);
        assertThat(work.observedBranch).isEqualTo(OwnershipBranch.RETRY_FAILED);
        assertThat(snapshot.get("performs").asInt()).isEqualTo(2);
        BaasDdlLog succeeded = ddlLogMapper.selectByProjectAndOperation(project.getId(), operationId);
        assertThat(succeeded.getRetryCount()).isEqualTo(1);
        assertThat(succeeded.getFenceEpoch()).isEqualTo(2L);
        assertThat(projectMapper.selectById(project.getId()).getDdlFenceEpoch()).isEqualTo(2L);
    }

    @Test
    void staleRunningIsTakenOverAndOldExecutorFencedOut() {
        BaasProject project = newProject();
        String operationId = UUID.randomUUID().toString();
        BaasDdlLog stale = new BaasDdlLog();
        stale.setProjectId(project.getId());
        stale.setOperationId(operationId);
        stale.setOperationType(DdlOperationType.CREATE.code());
        stale.setTableName("demo");
        stale.setRequestHash("a".repeat(64));
        stale.setStep(DdlStep.DDL_APPLIED.name());
        stale.setStatus(DdlLogStatus.RUNNING.name());
        stale.setOwnerToken("dead-executor");
        stale.setFenceEpoch(0L);
        stale.setRetryCount(0);
        ddlLogMapper.insert(stale);

        TestWorks.RecordingWork work = new TestWorks.RecordingWork();
        ObjectNode snapshot = engine.execute(spec(project, operationId, "a".repeat(64)), work);

        assertThat(work.observedBranch).isEqualTo(OwnershipBranch.TAKE_OVER_RUNNING);
        assertThat(snapshot.get("performs").asInt()).isEqualTo(1);
        BaasDdlLog taken = ddlLogMapper.selectByProjectAndOperation(project.getId(), operationId);
        assertThat(taken.getStatus()).isEqualTo(DdlLogStatus.SUCCESS.name());
        assertThat(taken.getOwnerToken()).isNotEqualTo("dead-executor");

        assertThat(ddlLogMapper.finishGuarded(taken.getId(), "dead-executor", 0L, DdlLogStatus.FAILED.name(),
                DdlStep.PREPARED.name(), null, "late write")).isZero();
    }

    @Test
    void takeOverPreservesCheckpointStep() {
        BaasProject project = newProject();
        String operationId = UUID.randomUUID().toString();
        BaasDdlLog stale = new BaasDdlLog();
        stale.setProjectId(project.getId());
        stale.setOperationId(operationId);
        stale.setOperationType(DdlOperationType.CREATE.code());
        stale.setRequestHash("a".repeat(64));
        stale.setStep(DdlStep.DDL_APPLIED.name());
        stale.setStatus(DdlLogStatus.RUNNING.name());
        stale.setOwnerToken("dead-executor");
        stale.setRetryCount(0);
        ddlLogMapper.insert(stale);

        AtomicBoolean sawDdlApplied = new AtomicBoolean(false);
        DdlWork work = new DdlWork() {
            @Override
            public void validateInLock(DdlWorkContext context) {
            }

            @Override
            public ObjectNode perform(DdlWorkContext context) {
                sawDdlApplied.set(context.stepReached(DdlStep.DDL_APPLIED));
                return context.completeSuccess(() -> MAPPER.createObjectNode().put("resumed", true));
            }
        };
        engine.execute(spec(project, operationId, "a".repeat(64)), work);
        assertThat(sawDdlApplied).isTrue();
    }

    @Test
    void pendingClaimBranchClaimsExactlyOnce() {
        BaasProject project = newProject();
        String operationId = UUID.randomUUID().toString();
        BaasDdlLog pending = new BaasDdlLog();
        pending.setProjectId(project.getId());
        pending.setOperationId(operationId);
        pending.setOperationType(DdlOperationType.CLEANUP_DROP.code());
        pending.setTableName("demo");
        pending.setTableId(99L);
        pending.setRequestHash("c".repeat(64));
        pending.setStep(DdlStep.PREPARED.name());
        pending.setStatus(DdlLogStatus.PENDING.name());
        pending.setRetryCount(0);
        ddlLogMapper.insert(pending);

        TestWorks.RecordingWork work = new TestWorks.RecordingWork();
        DdlOperationSpec cleanupSpec = new DdlOperationSpec(project.getId(), operationId,
                DdlOperationType.CLEANUP_DROP, "demo", 99L, "c".repeat(64), null, null);
        engine.execute(cleanupSpec, work);

        assertThat(work.observedBranch).isEqualTo(OwnershipBranch.CLAIM_PENDING);
        BaasDdlLog claimed = ddlLogMapper.selectByProjectAndOperation(project.getId(), operationId);
        assertThat(claimed.getStatus()).isEqualTo(DdlLogStatus.SUCCESS.name());
        assertThat(claimed.getFenceEpoch()).isEqualTo(projectMapper.selectById(project.getId()).getDdlFenceEpoch());
    }

    @Test
    void incompatibleExistingDataBecomesConflictAndPersistsOnlyStableCode() {
        BaasProject project = newProject();
        String operationId = UUID.randomUUID().toString();
        DdlWork work = new DdlWork() {
            @Override
            public void validateInLock(DdlWorkContext context) {
            }

            @Override
            public ObjectNode perform(DdlWorkContext context) {
                var sql = new java.sql.SQLException("secret value in row", "22001", 1406);
                throw new org.springframework.dao.DataIntegrityViolationException("secret ALTER SQL", sql);
            }
        };

        assertThatThrownBy(() -> engine.execute(spec(project, operationId, "a".repeat(64)), work))
            .isInstanceOf(DdlConflictException.class)
            .hasMessage("DDL 与现有数据不兼容");
        BaasDdlLog failed = ddlLogMapper.selectByProjectAndOperation(project.getId(), operationId);
        assertThat(failed.getErrorMsg()).isEqualTo("DDL_DATA_CONFLICT");
        assertThat(failed.getErrorMsg()).doesNotContain("secret", "ALTER", "row");
    }

    @Test
    void unknownSqlFailureNeverPersistsOrReturnsRawDatabaseText() {
        BaasProject project = newProject();
        String operationId = UUID.randomUUID().toString();
        DdlWork work = new DdlWork() {
            @Override
            public void validateInLock(DdlWorkContext context) {
            }

            @Override
            public ObjectNode perform(DdlWorkContext context) {
                var sql = new java.sql.SQLException("secret_table.secret_column", "HY000", 9999);
                throw new org.springframework.jdbc.BadSqlGrammarException("ddl", "ALTER secret_table", sql);
            }
        };

        assertThatThrownBy(() -> engine.execute(spec(project, operationId, "a".repeat(64)), work))
            .isInstanceOf(com.aiwork.baas.exception.DdlExecutionException.class)
            .hasMessage("DDL 执行失败");
        String error = ddlLogMapper.selectByProjectAndOperation(project.getId(), operationId).getErrorMsg();
        assertThat(error).isEqualTo("DDL_EXECUTION_FAILED;sqlState=HY000;vendorCode=9999");
        assertThat(error).doesNotContain("secret_table", "secret_column", "ALTER");
    }

    @Test
    void staleExecutorFailureDoesNotWriteTerminalState() {
        BaasProject project = newProject();
        String operationId = UUID.randomUUID().toString();
        AtomicBoolean failureTxCalled = new AtomicBoolean(false);
        DdlWork work = new DdlWork() {
            @Override
            public void validateInLock(DdlWorkContext context) {
            }

            @Override
            public ObjectNode perform(DdlWorkContext context) {
                throw new StaleExecutorException("stale executor");
            }

            @Override
            public void onFailureTx(DdlWorkContext context) {
                failureTxCalled.set(true);
            }
        };

        assertThatThrownBy(() -> engine.execute(spec(project, operationId, "a".repeat(64)), work))
            .isInstanceOf(StaleExecutorException.class);
        BaasDdlLog logRecord = ddlLogMapper.selectByProjectAndOperation(project.getId(), operationId);
        assertThat(logRecord.getStatus()).isEqualTo(DdlLogStatus.RUNNING.name());
        assertThat(failureTxCalled).isFalse();
    }

    @Test
    void lostRedisLockBeforeFailureTerminalLeavesRunningAndSkipsFailureTx() {
        BaasProject project = newProject();
        String operationId = UUID.randomUUID().toString();
        AtomicBoolean failureTxCalled = new AtomicBoolean(false);
        DdlWork work = new DdlWork() {
            @Override
            public void validateInLock(DdlWorkContext context) {
            }

            @Override
            public ObjectNode perform(DdlWorkContext context) {
                lockManager.release(context.lockHandle());
                throw new IllegalStateException("perform failure after lost lock");
            }

            @Override
            public void onFailureTx(DdlWorkContext context) {
                failureTxCalled.set(true);
            }
        };

        assertThatThrownBy(() -> engine.execute(spec(project, operationId, "a".repeat(64)), work))
            .isInstanceOf(StaleExecutorException.class);
        BaasDdlLog logRecord = ddlLogMapper.selectByProjectAndOperation(project.getId(), operationId);
        assertThat(logRecord.getStatus()).isEqualTo(DdlLogStatus.RUNNING.name());
        assertThat(failureTxCalled).isFalse();
    }

    @Test
    void failureTxExceptionRollsBackAndReturnsSanitizedFinalizationFailure() {
        BaasProject project = newProject();
        String operationId = UUID.randomUUID().toString();
        DdlWork work = new DdlWork() {
            @Override
            public void validateInLock(DdlWorkContext context) {
            }

            @Override
            public ObjectNode perform(DdlWorkContext context) {
                throw new IllegalStateException("perform failure");
            }

            @Override
            public void onFailureTx(DdlWorkContext context) {
                BaasProject marker = projectMapper.selectById(context.spec().projectId());
                marker.setName("failure-marker");
                projectMapper.updateById(marker);
                throw new IllegalArgumentException("secret finalization failure");
            }
        };

        Throwable failure = catchThrowable(
                () -> engine.execute(spec(project, operationId, "a".repeat(64)), work));

        assertThat(failure).isInstanceOf(DdlExecutionException.class)
            .hasMessage("DDL 执行失败")
            .hasNoCause();
        assertThat(((DdlExecutionException) failure).errorCode()).isEqualTo("DDL_FAILURE_FINALIZATION_FAILED");
        assertThat(ddlLogMapper.selectByProjectAndOperation(project.getId(), operationId).getStatus())
            .isEqualTo(DdlLogStatus.RUNNING.name());
        assertThat(projectMapper.selectById(project.getId()).getName()).isEqualTo("engine");
    }

    @Test
    void lostRedisLockAfterOwnershipTxPreventsPerform() {
        BaasProject project = newProject();
        String operationId = UUID.randomUUID().toString();
        AtomicInteger performCount = new AtomicInteger();
        DdlWork work = new DdlWork() {
            @Override
            public void validateInLock(DdlWorkContext context) {
            }

            @Override
            public void inOwnershipTx(DdlWorkContext context) {
                lockManager.release(context.lockHandle());
            }

            @Override
            public ObjectNode perform(DdlWorkContext context) {
                performCount.incrementAndGet();
                return MAPPER.createObjectNode();
            }
        };

        assertThatThrownBy(() -> engine.execute(spec(project, operationId, "a".repeat(64)), work))
            .isInstanceOf(StaleExecutorException.class);
        assertThat(performCount).hasValue(0);
        assertThat(ddlLogMapper.selectByProjectAndOperation(project.getId(), operationId).getStatus())
            .isEqualTo(DdlLogStatus.RUNNING.name());
    }

    @Test
    void failureFinalizationWarningUsesOnlyStableCode(CapturedOutput output) {
        BaasProject project = newProject();
        String operationId = UUID.randomUUID().toString();
        DdlWork work = new DdlWork() {
            @Override
            public void validateInLock(DdlWorkContext context) {
            }

            @Override
            public ObjectNode perform(DdlWorkContext context) {
                var sql = new java.sql.SQLException("secret_table.secret_column", "HY000", 9999);
                throw new org.springframework.jdbc.BadSqlGrammarException("ddl", "ALTER secret_table", sql);
            }

            @Override
            public void onFailureTx(DdlWorkContext context) {
                throw new IllegalStateException("secret finalization failure");
            }
        };

        Throwable failure = catchThrowable(() -> engine.execute(spec(project, operationId, "a".repeat(64)), work));

        assertThat(failure).isInstanceOf(DdlExecutionException.class);
        assertThat(((DdlExecutionException) failure).errorCode())
            .isEqualTo("DDL_FAILURE_FINALIZATION_FAILED");
        String warningLine = output.getOut()
            .lines()
            .filter(line -> line.contains("mark ddl failed operationId=" + operationId))
            .findFirst()
            .orElseThrow();
        assertThat(warningLine).contains("operationId=" + operationId, "errorType=IllegalStateException",
                "errorCode=DDL_FAILURE_FINALIZATION_FAILED")
            .doesNotContain("originalCode=", "sqlState=HY000", "vendorCode=9999", "secret finalization");
    }

    @Test
    void metadataWritesRollbackWhenSuccessTerminalGuardFails() {
        BaasProject project = newProject();
        String operationId = UUID.randomUUID().toString();
        AtomicReference<String> ownerToken = new AtomicReference<>();
        DdlWork work = new DdlWork() {
            @Override
            public void validateInLock(DdlWorkContext context) {
            }

            @Override
            public ObjectNode perform(DdlWorkContext context) {
                context.advanceToDdlApplied();
                ownerToken.set(context.ownerToken());
                return context.completeSuccess(() -> {
                    BaasProject marker = projectMapper.selectById(context.spec().projectId());
                    marker.setName("metadata-marker");
                    projectMapper.updateById(marker);
                    BaasDdlLog competing = ddlLogMapper.selectByProjectAndOperation(context.spec().projectId(),
                            context.spec().operationId());
                    competing.setOwnerToken("competing-owner");
                    ddlLogMapper.updateById(competing);
                    return MAPPER.createObjectNode().put("mustRollback", true);
                });
            }
        };

        assertThatThrownBy(() -> engine.execute(spec(project, operationId, "a".repeat(64)), work))
            .isInstanceOf(StaleExecutorException.class);
        BaasDdlLog logRecord = ddlLogMapper.selectByProjectAndOperation(project.getId(), operationId);
        assertThat(projectMapper.selectById(project.getId()).getName()).isEqualTo("engine");
        assertThat(logRecord.getStatus()).isEqualTo(DdlLogStatus.RUNNING.name());
        assertThat(logRecord.getStep()).isEqualTo(DdlStep.DDL_APPLIED.name());
        assertThat(logRecord.getOwnerToken()).isEqualTo(ownerToken.get());
        assertThat(logRecord.getResultSnapshot()).isNull();
    }

}
