package com.aiwork.baas.service;

import com.aiwork.baas.data.enduser.AuthProperties;
import com.aiwork.baas.entity.BaasAuditLog;
import com.aiwork.baas.entity.BaasJwtKey;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.enums.JwtKeyStatus;
import com.aiwork.baas.exception.DdlConflictException;
import com.aiwork.baas.mapper.BaasAuditLogMapper;
import com.aiwork.baas.mapper.BaasJwtKeyMapper;
import com.aiwork.baas.security.crypto.BaasCryptoService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * JWT 密钥轮换(spec §6.1/§7.6):平台库事务内 FOR UPDATE 锁全部密钥行,防并发双 CURRENT。
 * 常规轮换:存在未过期 PREVIOUS 拒绝;CURRENT → PREVIOUS(valid_until = now + access TTL)+ 新 CURRENT。
 * 紧急轮换:全部 CURRENT/PREVIOUS → REVOKED + 新 CURRENT,高等级审计。
 *
 * @author ai-work
 * @date 2026/07/22
 */
@Service
@RequiredArgsConstructor
public class JwtKeyRotationService {

    public record RotatedKey(String kid) {
    }

    private final BaasJwtKeyMapper jwtKeyMapper;

    private final BaasAuditLogMapper auditLogMapper;

    private final BaasCryptoService cryptoService;

    private final AuthProperties authProperties;

    private final TransactionTemplate transactionTemplate;

    public RotatedKey rotate(BaasProject project, Long operatorUserId) {
        rejectAmbientTransaction();
        return transactionTemplate.execute(status -> {
            List<BaasJwtKey> keys = jwtKeyMapper.selectByProjectForUpdate(project.getId());
            BaasJwtKey current = keys.stream()
                .filter(key -> key.getStatus() == JwtKeyStatus.CURRENT)
                .findFirst()
                .orElseThrow(() -> new DdlConflictException("项目缺少 CURRENT 签发密钥"));
            LocalDateTime now = LocalDateTime.now();
            boolean unexpiredPrevious = keys.stream()
                .anyMatch(key -> key.getStatus() == JwtKeyStatus.PREVIOUS && key.getValidUntil() != null
                        && key.getValidUntil().isAfter(now));
            if (unexpiredPrevious) {
                throw new DdlConflictException("存在未过期的 previous 密钥,拒绝常规轮换");
            }
            jwtKeyMapper.update(null, Wrappers.<BaasJwtKey>lambdaUpdate()
                .eq(BaasJwtKey::getId, current.getId())
                .set(BaasJwtKey::getStatus, JwtKeyStatus.PREVIOUS)
                .set(BaasJwtKey::getValidUntil, now.plusSeconds(authProperties.getAccessTtlSeconds())));
            String kid = insertCurrentKey(project.getId());
            audit(project.getId(), operatorUserId, "JWT_KEY_ROTATE", "kid=" + kid, "INFO");
            return new RotatedKey(kid);
        });
    }

    public RotatedKey emergencyRotate(BaasProject project, Long operatorUserId) {
        rejectAmbientTransaction();
        return transactionTemplate.execute(status -> {
            jwtKeyMapper.selectByProjectForUpdate(project.getId());
            jwtKeyMapper.update(null, Wrappers.<BaasJwtKey>lambdaUpdate()
                .eq(BaasJwtKey::getProjectId, project.getId())
                .in(BaasJwtKey::getStatus, JwtKeyStatus.CURRENT, JwtKeyStatus.PREVIOUS)
                .set(BaasJwtKey::getStatus, JwtKeyStatus.REVOKED));
            String kid = insertCurrentKey(project.getId());
            // 旧 access JWT 全部立即失效为预期代价;会话与 refresh token 不撤销(§6.1)
            audit(project.getId(), operatorUserId, "JWT_KEY_EMERGENCY_ROTATE", "kid=" + kid, "HIGH");
            return new RotatedKey(kid);
        });
    }

    private void rejectAmbientTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("project key operation cannot run within an active transaction");
        }
    }

    private String insertCurrentKey(Long projectId) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        BaasJwtKey jwtKey = new BaasJwtKey();
        jwtKey.setProjectId(projectId);
        jwtKey.setKid(UUID.randomUUID().toString());
        jwtKey.setSecretCipher(cryptoService.encrypt(Base64.getEncoder().encodeToString(secret),
                projectId + ":jwt_secret:" + jwtKey.getKid()));
        jwtKey.setStatus(JwtKeyStatus.CURRENT);
        jwtKeyMapper.insert(jwtKey);
        return jwtKey.getKid();
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
