package com.aiwork.baas.data.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.aiwork.baas.data.config.DataPlaneProperties;
import com.aiwork.baas.data.error.DataApiException;
import com.aiwork.baas.entity.BaasJwtKey;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.enums.JwtKeyStatus;
import com.aiwork.baas.mapper.BaasJwtKeyMapper;
import com.aiwork.baas.security.crypto.BaasCryptoService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * 终端用户 access JWT 完整验签(spec §7.5 逐项清单,缺一即 401;密钥每请求直查、零缓存)。
 *
 * @author ai-work
 * @date 2026/07/21
 */
@Component
@RequiredArgsConstructor
public class BaasJwtVerifier {

    private final BaasJwtKeyMapper jwtKeyMapper;

    private final BaasCryptoService cryptoService;

    private final DataPlaneProperties properties;

    public VerifiedEndUser verify(String token, BaasProject project) {
        SignedJWT jwt = parse(token);
        // ① alg 钉死 HS256,不以 token 自带 alg 选择算法
        if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) {
            throw unauthorized();
        }
        String kid = jwt.getHeader().getKeyID();
        if (kid == null) {
            throw unauthorized();
        }
        BaasJwtKey key = resolveKey(project.getId(), kid);
        verifySignature(jwt, project, key);
        JWTClaimsSet claims = claimsOf(jwt);
        // ② 必需 claim:iss/aud/sub/role/session_id/iat/exp
        String issuer = claims.getIssuer();
        List<String> audience = claims.getAudience();
        String subject = claims.getSubject();
        Object role = claims.getClaim("role");
        Object sessionId = claims.getClaim("session_id");
        Date issueTime = claims.getIssueTime();
        Date expirationTime = claims.getExpirationTime();
        if (issuer == null || audience == null || audience.isEmpty() || subject == null || role == null
                || sessionId == null || issueTime == null || expirationTime == null) {
            throw unauthorized();
        }
        // ③ iss/aud/role 严格匹配(§7.4 三方一致性),sub 严格解析 Long
        if (!("baas/" + project.getProjectRef()).equals(issuer)
                || !audience.equals(List.of(project.getProjectRef())) || !"authenticated".equals(role)) {
            throw unauthorized();
        }
        long userId = parseSubject(subject);
        // ④ exp 未过期、iat 不在未来(时钟偏差 60 秒)
        Instant now = Instant.now();
        long skewSeconds = properties.getJwtClockSkewSeconds();
        if (expirationTime.toInstant().plusSeconds(skewSeconds).isBefore(now)
                || issueTime.toInstant().minusSeconds(skewSeconds).isAfter(now)) {
            throw unauthorized();
        }
        // ⑤ exp − iat ≤ 1 小时
        long ttlSeconds = (expirationTime.getTime() - issueTime.getTime()) / 1000;
        if (ttlSeconds > properties.getJwtMaxTtlSeconds()) {
            throw unauthorized();
        }
        return new VerifiedEndUser(userId, String.valueOf(sessionId));
    }

    private BaasJwtKey resolveKey(Long projectId, String kid) {
        BaasJwtKey key = jwtKeyMapper.selectOne(Wrappers.<BaasJwtKey>lambdaQuery()
            .eq(BaasJwtKey::getProjectId, projectId)
            .eq(BaasJwtKey::getKid, kid));
        if (key == null) {
            throw unauthorized();
        }
        if (key.getStatus() == JwtKeyStatus.CURRENT) {
            return key;
        }
        if (key.getStatus() == JwtKeyStatus.PREVIOUS && key.getValidUntil() != null
                && key.getValidUntil().isAfter(LocalDateTime.now())) {
            return key;
        }
        throw unauthorized();
    }

    private void verifySignature(SignedJWT jwt, BaasProject project, BaasJwtKey key) {
        try {
            String secretBase64 = cryptoService.decrypt(key.getSecretCipher(),
                    project.getId() + ":jwt_secret:" + key.getKid());
            byte[] secret = Base64.getDecoder().decode(secretBase64);
            if (!jwt.verify(new MACVerifier(secret))) {
                throw unauthorized();
            }
        }
        catch (DataApiException exception) {
            throw exception;
        }
        catch (Exception exception) {
            throw unauthorized();
        }
    }

    private static SignedJWT parse(String token) {
        try {
            return SignedJWT.parse(token);
        }
        catch (Exception exception) {
            throw unauthorized();
        }
    }

    private static JWTClaimsSet claimsOf(SignedJWT jwt) {
        try {
            return jwt.getJWTClaimsSet();
        }
        catch (Exception exception) {
            throw unauthorized();
        }
    }

    private static long parseSubject(String subject) {
        try {
            return Long.parseLong(subject);
        }
        catch (NumberFormatException exception) {
            throw unauthorized();
        }
    }

    private static DataApiException unauthorized() {
        return DataApiException.unauthorized("JWT 无效");
    }

}
