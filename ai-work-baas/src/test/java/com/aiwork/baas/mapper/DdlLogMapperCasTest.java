package com.aiwork.baas.mapper;

import com.aiwork.baas.LifecycleTestApplication;
import com.aiwork.baas.entity.BaasDdlLog;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.enums.DdlLogStatus;
import com.aiwork.baas.entity.enums.DdlOperationType;
import com.aiwork.baas.entity.enums.DdlStep;
import com.aiwork.baas.entity.enums.ProjectStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = LifecycleTestApplication.class, properties = { "spring.config.import=",
        "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false" })
@Testcontainers
class DdlLogMapperCasTest {

    @Container
    static MySQLContainer mysql = new MySQLContainer("mysql:8.4").withUsername("root")
        .withPassword("root")
        .withDatabaseName("ai_work_baas")
        .withInitScript("init-metadata.sql");

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
        registry.add("server.servlet.context-path", () -> "");
    }

    @Autowired
    private BaasDdlLogMapper ddlLogMapper;

    @Autowired
    private BaasProjectMapper projectMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private BaasProject newProject() {
        BaasProject project = new BaasProject();
        project.setProjectRef(UUID.randomUUID().toString().substring(0, 16));
        project.setName("cas-test");
        project.setDbName("baas_" + project.getProjectRef().replace("-", ""));
        project.setStatus(ProjectStatus.ACTIVE);
        project.setOwnerUserId(1L);
        projectMapper.insert(project);
        return project;
    }

    private BaasDdlLog newLog(Long projectId, DdlLogStatus status, String ownerToken) {
        BaasDdlLog ddlLog = new BaasDdlLog();
        ddlLog.setProjectId(projectId);
        ddlLog.setOperationId(UUID.randomUUID().toString());
        ddlLog.setOperationType(DdlOperationType.CREATE.code());
        ddlLog.setTableName("demo");
        ddlLog.setRequestHash("h".repeat(64));
        ddlLog.setStep(DdlStep.PREPARED.name());
        ddlLog.setStatus(status.name());
        ddlLog.setOwnerToken(ownerToken);
        ddlLog.setRetryCount(0);
        ddlLogMapper.insert(ddlLog);
        return ddlLog;
    }

    @Test
    void projectStartsWithZeroFenceEpochAndBumpIsCas() {
        BaasProject project = newProject();
        assertThat(projectMapper.selectById(project.getId()).getDdlFenceEpoch()).isZero();

        assertThat(projectMapper.bumpFenceEpoch(project.getId(), 0L)).isEqualTo(1);
        assertThat(projectMapper.bumpFenceEpoch(project.getId(), 0L)).isZero();
        assertThat(projectMapper.selectById(project.getId()).getDdlFenceEpoch()).isEqualTo(1L);
    }

    @Test
    void retryFailedCasOnlyOneWinner() {
        BaasProject project = newProject();
        BaasDdlLog ddlLog = newLog(project.getId(), DdlLogStatus.FAILED, "old-token");

        int first = ddlLogMapper.casRetryFailed(ddlLog.getId(), "old-token", "new-token-a", 1L);
        int second = ddlLogMapper.casRetryFailed(ddlLog.getId(), "old-token", "new-token-b", 2L);

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        BaasDdlLog reloaded = ddlLogMapper.selectById(ddlLog.getId());
        assertThat(reloaded.getOwnerToken()).isEqualTo("new-token-a");
        assertThat(reloaded.getStatus()).isEqualTo(DdlLogStatus.RUNNING.name());
        assertThat(reloaded.getRetryCount()).isEqualTo(1);
        assertThat(reloaded.getFenceEpoch()).isEqualTo(1L);
        assertThat(reloaded.getUpdateTime()).isNotNull();
    }

    @Test
    void takeOverRunningCasRejectsWrongObservedToken() {
        BaasProject project = newProject();
        BaasDdlLog ddlLog = newLog(project.getId(), DdlLogStatus.RUNNING, "crashed-token");

        assertThat(ddlLogMapper.casTakeOverRunning(ddlLog.getId(), "someone-else", "new-token", 1L)).isZero();
        assertThat(ddlLogMapper.casTakeOverRunning(ddlLog.getId(), "crashed-token", "new-token", 1L)).isEqualTo(1);
    }

    @Test
    void claimPendingRequiresNullOwnerAndOnlyOneWinner() {
        BaasProject project = newProject();
        BaasDdlLog ddlLog = newLog(project.getId(), DdlLogStatus.PENDING, null);

        int first = ddlLogMapper.casClaimPending(ddlLog.getId(), "claimer-a", 1L);
        int second = ddlLogMapper.casClaimPending(ddlLog.getId(), "claimer-b", 2L);

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(ddlLogMapper.selectById(ddlLog.getId()).getOwnerToken()).isEqualTo("claimer-a");
    }

    @Test
    void guardedUpdatesNoOpForStaleOwner() {
        BaasProject project = newProject();
        BaasDdlLog ddlLog = newLog(project.getId(), DdlLogStatus.RUNNING, "owner-b");
        ddlLogMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.<BaasDdlLog>lambdaUpdate()
            .eq(BaasDdlLog::getId, ddlLog.getId())
            .set(BaasDdlLog::getFenceEpoch, 5L));

        assertThat(ddlLogMapper.advanceStepGuarded(ddlLog.getId(), "owner-a", DdlStep.DDL_APPLIED.name())).isZero();
        assertThat(ddlLogMapper.finishGuarded(ddlLog.getId(), "owner-a", 5L, DdlLogStatus.FAILED.name(),
                DdlStep.PREPARED.name(), null, "stale")).isZero();
        assertThat(ddlLogMapper.finishGuarded(ddlLog.getId(), "owner-b", 4L, DdlLogStatus.SUCCESS.name(),
                DdlStep.METADATA_APPLIED.name(), "{}", null)).isZero();

        assertThat(ddlLogMapper.advanceStepGuarded(ddlLog.getId(), "owner-b", DdlStep.DDL_APPLIED.name()))
            .isEqualTo(1);
        assertThat(ddlLogMapper.finishGuarded(ddlLog.getId(), "owner-b", 5L, DdlLogStatus.SUCCESS.name(),
                DdlStep.METADATA_APPLIED.name(), "{}", null)).isEqualTo(1);
        assertThat(ddlLogMapper.selectById(ddlLog.getId()).getStatus()).isEqualTo(DdlLogStatus.SUCCESS.name());
    }

    @Test
    void uniqueKeyIsPerProjectAndOperation() {
        BaasProject projectA = newProject();
        BaasProject projectB = newProject();
        BaasDdlLog logA = newLog(projectA.getId(), DdlLogStatus.RUNNING, "t");
        BaasDdlLog logB = new BaasDdlLog();
        logB.setProjectId(projectB.getId());
        logB.setOperationId(logA.getOperationId());
        logB.setOperationType(DdlOperationType.CREATE.code());
        logB.setRequestHash("h".repeat(64));
        logB.setStep(DdlStep.PREPARED.name());
        logB.setStatus(DdlLogStatus.RUNNING.name());
        logB.setRetryCount(0);

        assertThat(ddlLogMapper.insert(logB)).isEqualTo(1);
    }

}
