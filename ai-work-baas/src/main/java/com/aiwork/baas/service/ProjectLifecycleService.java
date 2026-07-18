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

package com.aiwork.baas.service;

import com.aiwork.baas.datasource.ProjectDataSourceRegistry;
import com.aiwork.baas.ddl.engine.DdlFencingGuard;
import com.aiwork.baas.ddl.lock.DdlLockBusyException;
import com.aiwork.baas.ddl.lock.ProjectDdlLockExecutor;
import com.aiwork.baas.ddl.render.DdlRenderer;
import com.aiwork.baas.entity.BaasApiKey;
import com.aiwork.baas.entity.BaasAuditLog;
import com.aiwork.baas.entity.BaasJwtKey;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.enums.JwtKeyStatus;
import com.aiwork.baas.entity.enums.KeyType;
import com.aiwork.baas.entity.enums.ProjectStatus;
import com.aiwork.baas.entity.enums.ProvisionStep;
import com.aiwork.baas.exception.DdlConflictException;
import com.aiwork.baas.exception.ProjectProvisionException;
import com.aiwork.baas.mapper.BaasApiKeyMapper;
import com.aiwork.baas.mapper.BaasAuditLogMapper;
import com.aiwork.baas.mapper.BaasJwtKeyMapper;
import com.aiwork.baas.mapper.BaasProjectMapper;
import com.aiwork.baas.provision.PhysicalPreconditions;
import com.aiwork.baas.provision.ProjectProvisioner;
import com.aiwork.baas.security.crypto.BaasCryptoService;
import com.aiwork.baas.security.key.ApiKeyGenerator;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 项目生命周期状态机(spec §9.1、§9.3)。平台库操作按三段短事务组织：元数据创建、开通完成、删除意图；
 * 外部副作用放在事务边界之外，状态迁移通过带 status 条件的更新实现 CAS。
 *
 * @author ai-work
 * @date 2026/07/17
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectLifecycleService {

    private final BaasProjectMapper projectMapper;

    private final BaasJwtKeyMapper jwtKeyMapper;

    private final BaasApiKeyMapper apiKeyMapper;

    private final BaasAuditLogMapper auditLogMapper;

    private final ProjectProvisioner provisioner;

    private final PhysicalPreconditions physicalPreconditions;

    private final ApiKeyGenerator keyGenerator;

    private final BaasCryptoService cryptoService;

    private final ProjectDataSourceRegistry registry;

    private final TransactionTemplate transactionTemplate;

    private final ProjectDdlLockExecutor lockExecutor;

    private final DdlFencingGuard fencingGuard;

    public record CreatedProject(BaasProject project, String publishableKey, String secretKey) {
    }

    public CreatedProject createProject(String name, Long ownerUserId) {
        physicalPreconditions.assertSatisfied();
        rejectAmbientTransaction();
        BaasProject project = transactionTemplate.execute(status -> {
            String projectRef = keyGenerator.generateProjectRef();
            BaasProject createdProject = new BaasProject();
            createdProject.setProjectRef(projectRef);
            createdProject.setName(name);
            createdProject.setDbName("baas_" + projectRef);
            createdProject.setStatus(ProjectStatus.PROVISIONING);
            createdProject.setProvisionStep(ProvisionStep.INIT.name());
            createdProject.setOwnerUserId(ownerUserId);
            createdProject.setRuntimeDbUser("rt_" + projectRef.substring(0, 12));
            projectMapper.insert(createdProject);

            String runtimePassword = keyGenerator.generateRuntimePassword();
            String passwordAad = aad(createdProject.getId(), "db_password", String.valueOf(createdProject.getId()));
            createdProject.setRuntimeDbPasswordCipher(cryptoService.encrypt(runtimePassword, passwordAad));
            projectMapper.updateById(createdProject);
            return createdProject;
        });
        AtomicReference<CreatedProject> completedProvision = new AtomicReference<>();
        try {
            return lockExecutor.execute(project.getId(), (handle, connection) -> {
                lockExecutor.assertStillHeld(handle);
                CreatedProject result = continueProvision(project, ownerUserId, connection);
                completedProvision.set(result);
                return result;
            });
        }
        catch (ProjectProvisionException provisionException) {
            throw provisionException;
        }
        catch (DdlLockBusyException busy) {
            if (completedProvision.get() != null) {
                return completedProvision.get();
            }
            markProvisionFailed(project, ownerUserId, busy);
            throw new ProjectProvisionException("provision lock unavailable", busy);
        }
        catch (RuntimeException infrastructureException) {
            markProvisionFailed(project, ownerUserId, infrastructureException);
            throw new ProjectProvisionException("provision lock infrastructure failed", infrastructureException);
        }
    }

    public CreatedProject retryProvision(Long projectId) {
        physicalPreconditions.assertSatisfied();
        rejectAmbientTransaction();
        AtomicReference<CreatedProject> completedProvision = new AtomicReference<>();
        try {
            return lockExecutor.execute(projectId, (handle, connection) -> {
                lockExecutor.assertStillHeld(handle);
                BaasProject project = transactionTemplate.execute(status -> prepareProvisionRetry(projectId));
                CreatedProject result = continueProvision(project, project.getOwnerUserId(), connection);
                completedProvision.set(result);
                return result;
            });
        }
        catch (DdlLockBusyException busy) {
            if (completedProvision.get() != null) {
                return completedProvision.get();
            }
            throw new DdlConflictException("该项目有 DDL 操作进行中，无法重试开通");
        }
    }

    public void deleteProject(Long projectId, Long operatorUserId) {
        rejectAmbientTransaction();
        BaasProject project;
        AtomicReference<BaasProject> committedDelete = new AtomicReference<>();
        try {
            project = lockExecutor.execute(projectId, (handle, connection) -> {
                lockExecutor.assertStillHeld(handle);
                BaasProject result = transactionTemplate.execute(status -> markDeleting(projectId, operatorUserId));
                committedDelete.set(result);
                return result;
            });
        }
        catch (DdlLockBusyException busy) {
            project = committedDelete.get();
            if (project == null) {
                throw new DdlConflictException("该项目有 DDL 操作进行中，无法删除");
            }
        }

        registry.blockAndDrain(project.getProjectRef());
    }

    /**
     * 对已进入 DELETING 状态的项目执行物理清理，由 {@link ProjectCleanupJob} 在延迟期到达后调用。
     * @param project 待清理项目
     * @return 本轮已完成清理或已跳过
     */
    public ProjectCleanupResult physicallyCleanup(BaasProject project) {
        physicalPreconditions.assertSatisfied();
        rejectAmbientTransaction();
        AtomicReference<ProjectCleanupResult> completedCleanup = new AtomicReference<>();
        try {
            return lockExecutor.execute(project.getId(), (handle, connection) -> {
                BaasProject current = projectMapper.selectById(project.getId());
                if (!cleanupDue(current)) {
                    completedCleanup.set(ProjectCleanupResult.SKIPPED);
                    return ProjectCleanupResult.SKIPPED;
                }

                lockExecutor.assertStillHeld(handle);
                long epoch = transactionTemplate.execute(
                        status -> fencingGuard.incrementEpochInTx(current.getId()));
                try (var statement = connection.createStatement()) {
                    statement.execute(DdlRenderer.renderDropDatabase(current.getDbName()).sql());
                    statement.execute(DdlRenderer.renderDropUser(current.getRuntimeDbUser()).sql());
                }

                lockExecutor.assertStillHeld(handle);
                transactionTemplate.executeWithoutResult(status -> {
                    fencingGuard.verifyEpochInTx(current.getId(), epoch);
                    if (!casStatus(current.getId(), ProjectStatus.DELETING, ProjectStatus.DELETED)) {
                        throw new IllegalStateException("project physical cleanup status race");
                    }
                });
                completedCleanup.set(ProjectCleanupResult.CLEANED);
                return ProjectCleanupResult.CLEANED;
            });
        }
        catch (DdlLockBusyException busy) {
            if (completedCleanup.get() != null) {
                return completedCleanup.get();
            }
            if (log.isInfoEnabled()) {
                log.info("project {} cleanup skipped: ddl lock busy", project.getProjectRef());
            }
            return ProjectCleanupResult.SKIPPED;
        }
    }

    private BaasProject prepareProvisionRetry(Long projectId) {
        BaasProject project = projectMapper.selectByIdForUpdate(projectId);
        if (project == null) {
            throw new IllegalStateException("project not found");
        }
        if (!casStatus(projectId, ProjectStatus.FAILED, ProjectStatus.PROVISIONING)) {
            throw new IllegalStateException("project not in FAILED state or concurrent retry");
        }

        project.setStatus(ProjectStatus.PROVISIONING);
        return project;
    }

    private BaasProject markDeleting(Long projectId, Long operatorUserId) {
        fencingGuard.incrementEpochInTx(projectId);
        boolean transitioned = casStatus(projectId, ProjectStatus.ACTIVE, ProjectStatus.DELETING)
                || casStatus(projectId, ProjectStatus.FAILED, ProjectStatus.DELETING);
        BaasProject deletingProject = projectMapper.selectById(projectId);
        if (deletingProject == null
                || (!transitioned && deletingProject.getStatus() != ProjectStatus.DELETING)) {
            throw new IllegalStateException("project not deletable or concurrent state change");
        }

        revokeActiveKeys(projectId);
        int scheduled = projectMapper.update(null, Wrappers.<BaasProject>lambdaUpdate()
            .eq(BaasProject::getId, projectId)
            .isNull(BaasProject::getDeleteAfter)
            .set(BaasProject::getDeleteAfter, LocalDateTime.now().plusDays(7)));
        if (transitioned && scheduled != 1) {
            throw new IllegalStateException("project delete_after update race");
        }
        if (transitioned) {
            audit(projectId, operatorUserId, "PROJECT_DELETE", "ref=" + deletingProject.getProjectRef(), "HIGH");
        }
        return deletingProject;
    }

    private boolean cleanupDue(BaasProject project) {
        return project != null && project.getStatus() == ProjectStatus.DELETING && project.getDeleteAfter() != null
                && !project.getDeleteAfter().isAfter(LocalDateTime.now());
    }

    private CreatedProject continueProvision(BaasProject project, Long operatorUserId, Connection connection) {
        try {
            ProvisionStep completedStep = ProvisionStep.valueOf(project.getProvisionStep());
            String passwordAad = aad(project.getId(), "db_password", String.valueOf(project.getId()));
            String runtimePassword = cryptoService.decrypt(project.getRuntimeDbPasswordCipher(), passwordAad);

            if (completedStep.ordinal() < ProvisionStep.DB_CREATED.ordinal()) {
                provisioner.createDatabase(connection, project.getDbName());
                advance(project, ProvisionStep.DB_CREATED);
            }
            if (completedStep.ordinal() < ProvisionStep.USER_CREATED.ordinal()) {
                provisioner.createRuntimeUser(connection, project.getRuntimeDbUser(), runtimePassword,
                        project.getDbName());
                advance(project, ProvisionStep.USER_CREATED);
            }
            if (completedStep.ordinal() < ProvisionStep.SYSTEM_TABLES.ordinal()) {
                provisioner.initSystemTables(connection, project.getDbName());
                advance(project, ProvisionStep.SYSTEM_TABLES);
            }

            ensureCurrentJwtKey(project);
            advance(project, ProvisionStep.JWT_KEY);

            ApiKeyGenerator.GeneratedKey publishableKey = keyGenerator.generatePublishable();
            ApiKeyGenerator.GeneratedKey secretKey = keyGenerator.generateSecret();
            transactionTemplate.executeWithoutResult(status -> {
                revokeActiveKeys(project.getId());
                insertKey(project.getId(), KeyType.PUBLISHABLE, publishableKey);
                insertKey(project.getId(), KeyType.SECRET, secretKey);
                advance(project, ProvisionStep.API_KEYS);
                if (!casStatus(project.getId(), ProjectStatus.PROVISIONING, ProjectStatus.ACTIVE)) {
                    throw new IllegalStateException("concurrent state change during provisioning");
                }

                audit(project.getId(), operatorUserId, "PROJECT_CREATE", "ref=" + project.getProjectRef(), "INFO");
            });
            project.setStatus(ProjectStatus.ACTIVE);
            return new CreatedProject(project, publishableKey.plaintext(), secretKey.plaintext());
        }
        catch (Exception exception) {
            markProvisionFailed(project, operatorUserId, exception);
            throw new ProjectProvisionException("provision failed at " + project.getProvisionStep(), exception);
        }
    }

    private void markProvisionFailed(BaasProject project, Long operatorUserId, Exception provisionException) {
        try {
            if (!casStatus(project.getId(), ProjectStatus.PROVISIONING, ProjectStatus.FAILED)) {
                provisionException.addSuppressed(
                        new IllegalStateException("project status changed while marking provision failed"));
            }
        }
        catch (Exception statusException) {
            provisionException.addSuppressed(statusException);
        }

        try {
            audit(project.getId(), operatorUserId, "PROJECT_PROVISION_FAILED",
                    "step=" + project.getProvisionStep(), "HIGH");
        }
        catch (Exception auditException) {
            provisionException.addSuppressed(auditException);
            log.error("audit project {} provision failure failed", project.getId(), auditException);
        }
    }

    private void ensureCurrentJwtKey(BaasProject project) {
        Long currentKeyCount = jwtKeyMapper.selectCount(Wrappers.<BaasJwtKey>lambdaQuery()
            .eq(BaasJwtKey::getProjectId, project.getId())
            .eq(BaasJwtKey::getStatus, JwtKeyStatus.CURRENT));
        if (currentKeyCount > 0) {
            return;
        }

        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        BaasJwtKey jwtKey = new BaasJwtKey();
        jwtKey.setProjectId(project.getId());
        jwtKey.setKid(UUID.randomUUID().toString());
        String secretAad = aad(project.getId(), "jwt_secret", jwtKey.getKid());
        jwtKey.setSecretCipher(cryptoService.encrypt(Base64.getEncoder().encodeToString(secret), secretAad));
        jwtKey.setStatus(JwtKeyStatus.CURRENT);
        jwtKeyMapper.insert(jwtKey);
    }

    private void revokeActiveKeys(Long projectId) {
        LambdaUpdateWrapper<BaasApiKey> updateWrapper = Wrappers.<BaasApiKey>lambdaUpdate()
            .eq(BaasApiKey::getProjectId, projectId)
            .eq(BaasApiKey::getStatus, "ACTIVE")
            .set(BaasApiKey::getStatus, "REVOKED")
            .set(BaasApiKey::getRevokeTime, LocalDateTime.now());
        apiKeyMapper.update(null, updateWrapper);
    }

    private void insertKey(Long projectId, KeyType keyType, ApiKeyGenerator.GeneratedKey generatedKey) {
        BaasApiKey apiKey = new BaasApiKey();
        apiKey.setProjectId(projectId);
        apiKey.setKeyType(keyType);
        apiKey.setKeyHash(generatedKey.hash());
        apiKey.setKeyPrefix(generatedKey.prefix());
        apiKey.setStatus("ACTIVE");
        apiKeyMapper.insert(apiKey);
    }

    private void advance(BaasProject project, ProvisionStep provisionStep) {
        int affectedRows = projectMapper.update(null, Wrappers.<BaasProject>lambdaUpdate()
            .eq(BaasProject::getId, project.getId())
            .eq(BaasProject::getStatus, ProjectStatus.PROVISIONING)
            .set(BaasProject::getProvisionStep, provisionStep.name()));
        if (affectedRows != 1) {
            throw new IllegalStateException("project not in PROVISIONING state during step advance");
        }
        project.setProvisionStep(provisionStep.name());
    }

    private void rejectAmbientTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("project lifecycle operation cannot run within an active transaction");
        }
    }

    private boolean casStatus(Long projectId, ProjectStatus fromStatus, ProjectStatus toStatus) {
        return projectMapper.update(null, Wrappers.<BaasProject>lambdaUpdate()
            .eq(BaasProject::getId, projectId)
            .eq(BaasProject::getStatus, fromStatus)
            .set(BaasProject::getStatus, toStatus)) > 0;
    }

    private void audit(Long projectId, Long operatorUserId, String action, String detail, String level) {
        BaasAuditLog auditLog = new BaasAuditLog();
        auditLog.setProjectId(projectId);
        auditLog.setOperatorUserId(operatorUserId);
        auditLog.setAction(action);
        auditLog.setDetail(detail);
        auditLog.setLevel(level);
        auditLogMapper.insert(auditLog);
    }

    private String aad(Long projectId, String fieldType, String recordId) {
        return projectId + ":" + fieldType + ":" + recordId;
    }

}
