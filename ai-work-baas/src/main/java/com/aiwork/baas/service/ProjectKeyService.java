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

import com.aiwork.baas.controller.dto.ApiKeyVO;
import com.aiwork.baas.controller.dto.CreatedKeyVO;
import com.aiwork.baas.entity.BaasApiKey;
import com.aiwork.baas.entity.BaasAuditLog;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.enums.KeyType;
import com.aiwork.baas.entity.enums.ProjectStatus;
import com.aiwork.baas.exception.ProjectNotFoundException;
import com.aiwork.baas.mapper.BaasApiKeyMapper;
import com.aiwork.baas.mapper.BaasAuditLogMapper;
import com.aiwork.baas.mapper.BaasProjectMapper;
import com.aiwork.baas.security.key.ApiKeyGenerator;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Studio API Key 与项目 CORS 配置服务。Key 变更使用独立短事务，并与审计写入保持原子性。
 *
 * @author ai-work
 * @date 2026/07/17
 */
@Service
@RequiredArgsConstructor
public class ProjectKeyService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final BaasApiKeyMapper apiKeyMapper;

    private final BaasProjectMapper projectMapper;

    private final BaasAuditLogMapper auditLogMapper;

    private final ApiKeyGenerator keyGenerator;

    private final TransactionTemplate transactionTemplate;

    public List<ApiKeyVO> listKeys(Long projectId) {
        return apiKeyMapper.selectList(Wrappers.<BaasApiKey>lambdaQuery()
            .eq(BaasApiKey::getProjectId, projectId))
            .stream()
            .map(apiKey -> new ApiKeyVO(String.valueOf(apiKey.getId()), apiKey.getKeyType().name(),
                    apiKey.getKeyPrefix(), apiKey.getStatus(), apiKey.getCreateTime()))
            .toList();
    }

    public CreatedKeyVO createKey(Long projectId, KeyType keyType, Long operatorUserId) {
        rejectAmbientTransaction();
        ApiKeyGenerator.GeneratedKey generatedKey = keyType == KeyType.PUBLISHABLE
                ? keyGenerator.generatePublishable() : keyGenerator.generateSecret();
        return transactionTemplate.execute(status -> {
            lockActiveProject(projectId);
            BaasApiKey apiKey = new BaasApiKey();
            apiKey.setProjectId(projectId);
            apiKey.setKeyType(keyType);
            apiKey.setKeyHash(generatedKey.hash());
            apiKey.setKeyPrefix(generatedKey.prefix());
            apiKey.setStatus("ACTIVE");
            apiKeyMapper.insert(apiKey);
            audit(projectId, operatorUserId, "KEY_CREATE",
                    "type=" + keyType + ", prefix=" + generatedKey.prefix(), "INFO");
            return new CreatedKeyVO(String.valueOf(apiKey.getId()), keyType.name(), generatedKey.plaintext());
        });
    }

    public void revokeKey(Long projectId, Long keyId, Long operatorUserId) {
        rejectAmbientTransaction();
        transactionTemplate.executeWithoutResult(status -> {
            int affectedRows = apiKeyMapper.update(null, Wrappers.<BaasApiKey>lambdaUpdate()
                .eq(BaasApiKey::getId, keyId)
                .eq(BaasApiKey::getProjectId, projectId)
                .eq(BaasApiKey::getStatus, "ACTIVE")
                .set(BaasApiKey::getStatus, "REVOKED")
                .set(BaasApiKey::getRevokeTime, LocalDateTime.now()));
            if (affectedRows == 0) {
                throw new ProjectNotFoundException();
            }

            audit(projectId, operatorUserId, "KEY_REVOKE", "keyId=" + keyId, "HIGH");
        });
    }

    public void updateAllowedOrigins(Long projectId, List<String> origins) {
        String originsJson;
        try {
            originsJson = origins == null ? null : OBJECT_MAPPER.writeValueAsString(origins);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid allowed origins", exception);
        }

        projectMapper.update(null, Wrappers.<BaasProject>lambdaUpdate()
            .eq(BaasProject::getId, projectId)
            .set(BaasProject::getAllowedOrigins, originsJson));
    }

    private void lockActiveProject(Long projectId) {
        BaasProject project = projectMapper.selectOne(Wrappers.<BaasProject>lambdaQuery()
            .eq(BaasProject::getId, projectId)
            .last("FOR UPDATE"));
        if (project == null || project.getStatus() != ProjectStatus.ACTIVE) {
            throw new ProjectNotFoundException();
        }
    }

    private void rejectAmbientTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("project key operation cannot run within an active transaction");
        }
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

}
