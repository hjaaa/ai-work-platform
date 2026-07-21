package com.aiwork.baas.data.auth;

import com.nimbusds.jwt.JWTClaimsSet;
import com.aiwork.baas.data.error.DataApiException;
import com.aiwork.baas.entity.BaasJwtKey;
import com.aiwork.baas.entity.enums.JwtKeyStatus;
import com.aiwork.baas.support.DataPlaneIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWT 验签逐项清单(spec §7.5:alg/claim 集/exp/iat/TTL/kid 三态)。
 *
 * @author ai-work
 * @date 2026/07/21
 */
class BaasJwtVerifierIntegrationTest extends DataPlaneIntegrationTestSupport {

    @Autowired
    private BaasJwtVerifier verifier;

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private void assert401(String token) {
        assertThatThrownBy(() -> verifier.verify(token, fixture.project())).isInstanceOf(DataApiException.class)
            .satisfies(e -> assertThat(((DataApiException)e).status()).isEqualTo(401));
    }

    @Test
    void validTokenReturnsUserAndSession() {
        VerifiedEndUser user = verifier.verify(mintJwt(42L, null), fixture.project());

        assertThat(user.userId()).isEqualTo(42L);
        assertThat(user.sessionId()).isNotBlank();
    }

    @Test
    void rejectsNoneAlgorithm() {
        String header = base64Url("{\"alg\":\"none\"}");
        String payload = base64Url("{\"sub\":\"1\"}");
        assert401(header + "." + payload + ".");
    }

    @Test
    void rejectsHs384Algorithm() {
        String kid = currentJwtKey().getKid();
        String header = base64Url("{\"alg\":\"HS384\",\"kid\":\"" + kid + "\"}");
        String payload = base64Url("{\"sub\":\"1\"}");
        assert401(header + "." + payload + "." + base64Url("fakesig"));
    }

    @Test
    void rejectsExpiredToken() {
        // exp 在 90 秒前:超出 60 秒时钟偏差
        assert401(mintJwt(1L, claims -> claims.expirationTime(Date.from(Instant.now().minusSeconds(90)))));
    }

    @Test
    void acceptsExpWithinClockSkew() {
        VerifiedEndUser user = verifier.verify(
                mintJwt(1L, claims -> claims.expirationTime(Date.from(Instant.now().minusSeconds(30)))),
                fixture.project());

        assertThat(user.userId()).isEqualTo(1L);
    }

    @Test
    void rejectsFutureIat() {
        assert401(mintJwt(1L, claims -> {
            Instant future = Instant.now().plusSeconds(120);
            claims.issueTime(Date.from(future));
            claims.expirationTime(Date.from(future.plusSeconds(600)));
        }));
    }

    @Test
    void rejectsTtlOverOneHour() {
        assert401(mintJwt(1L, claims -> {
            Instant now = Instant.now();
            claims.issueTime(Date.from(now));
            claims.expirationTime(Date.from(now.plusSeconds(3600 + 120)));
        }));
    }

    @Test
    void rejectsMissingRequiredClaims() {
        assert401(mintJwt(1L, claims -> claims.issuer(null)));
        assert401(mintJwt(1L, claims -> claims.audience(List.of())));
        assert401(mintJwt(1L, claims -> claims.subject(null)));
        assert401(mintJwt(1L, claims -> claims.expirationTime(null)));
        assert401(mintJwt(1L, claims -> claims.claim("session_id", null)));
        assert401(mintJwt(1L, claims -> claims.claim("role", null)));
        assert401(mintJwt(1L, claims -> claims.issueTime(null)));
    }

    @Test
    void rejectsWrongIssuerAudienceOrRole() {
        assert401(mintJwt(1L, claims -> claims.issuer("baas/other")));
        assert401(mintJwt(1L, claims -> claims.audience("other")));
        assert401(mintJwt(1L,
                claims -> claims.audience(List.of(fixture.project().getProjectRef(), "other"))));
        assert401(mintJwt(1L, claims -> claims.claim("role", "service_role")));
    }

    @Test
    void rejectsNonNumericSub() {
        assert401(mintJwt(1L, claims -> claims.subject("abc")));
    }

    @Test
    void rejectsUnknownKid() throws Exception {
        String unknownKidToken;
        {
            com.nimbusds.jwt.SignedJWT jwt = new com.nimbusds.jwt.SignedJWT(
                    new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.HS256)
                        .keyID("00000000-0000-0000-0000-000000000000")
                        .build(),
                    new JWTClaimsSet.Builder().issuer("baas/" + fixture.project().getProjectRef())
                        .audience(fixture.project().getProjectRef())
                        .subject("1")
                        .claim("role", "authenticated")
                        .claim("session_id", "s")
                        .issueTime(new Date())
                        .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                        .build());
            jwt.sign(new com.nimbusds.jose.crypto.MACSigner(currentJwtSecret()));
            unknownKidToken = jwt.serialize();
        }
        assert401(unknownKidToken);
    }

    @Test
    void acceptsUnexpiredPreviousKid() {
        String token = mintJwt(1L, null);
        BaasJwtKey key = currentJwtKey();
        key.setStatus(JwtKeyStatus.PREVIOUS);
        key.setValidUntil(LocalDateTime.now().plusMinutes(5));
        jwtKeyMapper.updateById(key);

        assertThat(verifier.verify(token, fixture.project()).userId()).isEqualTo(1L);
    }

    @Test
    void rejectsExpiredPreviousKid() {
        String token = mintJwt(1L, null);
        BaasJwtKey key = currentJwtKey();
        key.setStatus(JwtKeyStatus.PREVIOUS);
        key.setValidUntil(LocalDateTime.now().minusMinutes(1));
        jwtKeyMapper.updateById(key);

        assert401(token);
    }

    @Test
    void rejectsRevokedKid() {
        String token = mintJwt(1L, null);
        BaasJwtKey key = currentJwtKey();
        key.setStatus(JwtKeyStatus.REVOKED);
        key.setValidUntil(null);
        jwtKeyMapper.updateById(key);

        assert401(token);
    }

    @Test
    void revokedKeyIsRejectedOnTheNextVerification() {
        String token = mintJwt(1L, null);
        BaasJwtKey key = currentJwtKey();
        assertThat(verifier.verify(token, fixture.project()).userId()).isEqualTo(1L);

        key.setStatus(JwtKeyStatus.REVOKED);
        jwtKeyMapper.updateById(key);

        assert401(token);
    }

    @Test
    void rejectsJwtSecretEncryptedWithWrongAad() {
        String token = mintJwt(1L, null);
        BaasJwtKey key = currentJwtKey();
        String secretBase64 = Base64.getEncoder().encodeToString(new byte[32]);
        key.setSecretCipher(cryptoService.encrypt(secretBase64, "wrong-aad"));
        jwtKeyMapper.updateById(key);

        assert401(token);
    }

    @Test
    void rejectsTamperedSignature() {
        String token = mintJwt(1L, null);
        assert401(token.substring(0, token.length() - 4) + "AAAA");
    }

    @Test
    void garbageTokenRejected() {
        assert401("not-a-jwt");
    }

}
