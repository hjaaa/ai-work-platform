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

import java.math.BigInteger;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 终端用户 access JWT 完整验签(spec §7.5 逐项清单,缺一即 401;密钥每请求直查、零缓存)。
 *
 * @author ai-work
 * @date 2026/07/21
 */
@Component
@RequiredArgsConstructor
public class BaasJwtVerifier {

    private static final String SUBJECT_CLAIM = "sub";

    private static final String ISSUED_AT_CLAIM = "iat";

    private static final String EXPIRATION_TIME_CLAIM = "exp";

    private final BaasJwtKeyMapper jwtKeyMapper;

    private final BaasCryptoService cryptoService;

    private final DataPlaneProperties properties;

    private final Clock clock;

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
        Map<String, Object> rawClaims = rawClaimsOf(jwt);
        Object rawSubject = rawClaims.get(SUBJECT_CLAIM);
        if (!(rawSubject instanceof String subject)) {
            throw unauthorized();
        }
        long issueTime = integerNumericDate(rawClaims, ISSUED_AT_CLAIM);
        long expirationTime = integerNumericDate(rawClaims, EXPIRATION_TIME_CLAIM);
        JWTClaimsSet claims = claimsOf(jwt);
        // ② 必需 claim:iss/aud/sub/role/session_id/iat/exp
        String issuer = claims.getIssuer();
        List<String> audience = claims.getAudience();
        Object role = claims.getClaim("role");
        Object sessionId = claims.getClaim("session_id");
        if (issuer == null || audience == null || audience.isEmpty() || role == null || sessionId == null) {
            throw unauthorized();
        }
        // ③ iss/aud/role 严格匹配(§7.4 三方一致性),sub 严格解析 Long
        if (!("baas/" + project.getProjectRef()).equals(issuer)
                || !audience.equals(List.of(project.getProjectRef())) || !"authenticated".equals(role)) {
            throw unauthorized();
        }
        long userId = parseSubject(subject);
        validateTimeline(issueTime, expirationTime);
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

    private static Map<String, Object> rawClaimsOf(SignedJWT jwt) {
        Map<String, Object> claims = jwt.getPayload().toJSONObject();
        if (claims == null) {
            throw unauthorized();
        }
        return claims;
    }

    private static long integerNumericDate(Map<String, Object> claims, String claimName) {
        Object value = claims.get(claimName);
        if (!(value instanceof Long numericDate)) {
            throw unauthorized();
        }
        return numericDate;
    }

    private void validateTimeline(long issueTime, long expirationTime) {
        BigInteger issuedAt = BigInteger.valueOf(issueTime);
        BigInteger expiresAt = BigInteger.valueOf(expirationTime);
        if (expiresAt.compareTo(issuedAt) <= 0) {
            throw unauthorized();
        }

        BigInteger now = BigInteger.valueOf(clock.instant().getEpochSecond());
        BigInteger skew = BigInteger.valueOf(properties.getJwtClockSkewSeconds());
        if (expiresAt.add(skew).compareTo(now) < 0 || issuedAt.subtract(skew).compareTo(now) > 0) {
            throw unauthorized();
        }

        BigInteger maxTtl = BigInteger.valueOf(properties.getJwtMaxTtlSeconds());
        if (expiresAt.subtract(issuedAt).compareTo(maxTtl) > 0) {
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
