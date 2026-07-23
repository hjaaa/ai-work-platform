package com.aiwork.baas.data.enduser;

import com.aiwork.baas.data.error.DataApiException;
import com.aiwork.baas.entity.BaasJwtKey;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.enums.JwtKeyStatus;
import com.aiwork.baas.mapper.BaasJwtKeyMapper;
import com.aiwork.baas.security.crypto.BaasCryptoService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * access JWT 签发器(spec §7.6):每次签发直查项目 CURRENT baas_jwt_key(不缓存,
 * 与 Plan C 验签对称支撑 §6.1 紧急轮换立即生效);claims 按 §7.2 固定。
 *
 * @author ai-work
 * @date 2026/07/22
 */
@Slf4j
@Component
public class BaasJwtSigner {

    public record SignedAccessToken(String token, long expiresInSeconds) {
    }

    private final BaasJwtKeyMapper jwtKeyMapper;

    private final BaasCryptoService cryptoService;

    private final AuthProperties properties;

    private final Clock clock;

    private final TransactionTemplate transactionTemplate;

    public BaasJwtSigner(BaasJwtKeyMapper jwtKeyMapper, BaasCryptoService cryptoService, AuthProperties properties,
            Clock clock, TransactionTemplate transactionTemplate) {
        this.jwtKeyMapper = jwtKeyMapper;
        this.cryptoService = cryptoService;
        this.properties = properties;
        this.clock = clock;
        this.transactionTemplate = transactionTemplate;
    }

    public SignedAccessToken sign(BaasProject project, long userId, long sessionId) {
        // 在一个显式平台库事务内完成 FOR SHARE 锁定读 + 签名:共享锁与 emergencyRotate 的 FOR UPDATE 互斥,
        // 且锁持有至签名完成、事务提交,轮换无法在读语句结束后、签名期间提交撤销该 kid(§6.1/§7.6)。
        return transactionTemplate.execute(status -> {
            BaasJwtKey key = selectCurrentKeyLocking(project.getId());
            if (key == null) {
                throw DataApiException.internal("签发密钥不可用");
            }
            return signWithKey(project, userId, sessionId, key);
        });
    }

    private BaasJwtKey selectCurrentKeyLocking(Long projectId) {
        return jwtKeyMapper.selectByProjectForShare(projectId).stream()
            .filter(key -> key.getStatus() == JwtKeyStatus.CURRENT)
            .findFirst()
            .orElse(null);
    }

    private SignedAccessToken signWithKey(BaasProject project, long userId, long sessionId, BaasJwtKey key) {
        try {
            String secretBase64 = cryptoService.decrypt(key.getSecretCipher(),
                    project.getId() + ":jwt_secret:" + key.getKid());
            byte[] secret = Base64.getDecoder().decode(secretBase64);
            Instant now = clock.instant();
            long expiresIn = properties.getAccessTtlSeconds();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("baas/" + project.getProjectRef())
                .audience(project.getProjectRef())
                .subject(String.valueOf(userId))
                .claim("role", "authenticated")
                .claim("session_id", sessionId)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(expiresIn)))
                .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(key.getKid()).build(), claims);
            jwt.sign(new MACSigner(secret));
            return new SignedAccessToken(jwt.serialize(), expiresIn);
        }
        catch (DataApiException exception) {
            throw exception;
        }
        catch (Exception exception) {
            log.error("jwt sign failed projectId={} errorType={}", project.getId(), exception.getClass().getSimpleName());
            throw DataApiException.internal("签发失败");
        }
    }

}
