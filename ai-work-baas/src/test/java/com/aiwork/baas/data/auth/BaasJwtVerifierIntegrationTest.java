package com.aiwork.baas.data.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.aiwork.baas.data.config.DataPlaneProperties;
import com.aiwork.baas.data.error.DataApiException;
import com.aiwork.baas.entity.BaasJwtKey;
import com.aiwork.baas.entity.enums.JwtKeyStatus;
import com.aiwork.baas.support.DataPlaneIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @Autowired
    private DataPlaneProperties properties;

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private void assert401(String token) {
        assert401(verifier, token);
    }

    private void assert401(BaasJwtVerifier jwtVerifier, String token) {
        assertThatThrownBy(() -> jwtVerifier.verify(token, fixture.project())).isInstanceOf(DataApiException.class)
            .satisfies(e -> assertThat(((DataApiException)e).status()).isEqualTo(401));
    }

    private BaasJwtVerifier verifierAt(Instant instant) {
        Clock fixedClock = Clock.fixed(instant, ZoneOffset.UTC);
        return new BaasJwtVerifier(jwtKeyMapper, cryptoService, properties, fixedClock);
    }

    private String mintRawJwt(Object subject, Number issuedAt, Number expirationTime) {
        try {
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("iss", "baas/" + fixture.project().getProjectRef());
            claims.put("aud", fixture.project().getProjectRef());
            claims.put("sub", subject);
            claims.put("role", "authenticated");
            claims.put("session_id", "s");
            claims.put("iat", issuedAt);
            claims.put("exp", expirationTime);
            JWSObject jwt = new JWSObject(
                    new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(currentJwtKey().getKid()).build(),
                    new Payload(claims));
            jwt.sign(new MACSigner(currentJwtSecret()));
            return jwt.serialize();
        }
        catch (Exception exception) {
            throw new IllegalStateException("mint raw jwt failed", exception);
        }
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
        assert401(mintJwt(1L, claims -> claims.expirationTime(Date.from(clock.instant().minusSeconds(90)))));
    }

    @Test
    void acceptsExpWithinClockSkew() {
        VerifiedEndUser user = verifier.verify(mintJwt(1L, claims -> {
            Instant now = clock.instant();
            claims.issueTime(Date.from(now.minusSeconds(90)));
            claims.expirationTime(Date.from(now.minusSeconds(30)));
        }), fixture.project());

        assertThat(user.userId()).isEqualTo(1L);
    }

    @Test
    void rejectsFutureIat() {
        assert401(mintJwt(1L, claims -> {
            Instant future = clock.instant().plusSeconds(120);
            claims.issueTime(Date.from(future));
            claims.expirationTime(Date.from(future.plusSeconds(600)));
        }));
    }

    @Test
    void rejectsTtlOverOneHour() {
        assert401(mintJwt(1L, claims -> {
            Instant now = clock.instant();
            claims.issueTime(Date.from(now));
            claims.expirationTime(Date.from(now.plusSeconds(3600 + 120)));
        }));
    }

    @Test
    void rejectsExpirationBeforeIssueTime() {
        Instant now = clock.instant();
        assert401(mintJwt(1L, claims -> {
            claims.issueTime(Date.from(now));
            claims.expirationTime(Date.from(now.minusSeconds(1)));
        }));
    }

    @Test
    void rejectsExpirationEqualToIssueTime() {
        Instant now = clock.instant();
        assert401(mintJwt(1L, claims -> {
            claims.issueTime(Date.from(now));
            claims.expirationTime(Date.from(now));
        }));
    }

    @Test
    void rejectsExtremeIssueTimeWithoutLifetimeOverflow() {
        long extremePast = Long.MIN_VALUE / 1000;
        assert401(mintRawJwt("1", extremePast, clock.instant().getEpochSecond() + 600));
    }

    @Test
    void rejectsNumericSubjectBeforeNimbusNormalization() {
        long now = clock.instant().getEpochSecond();
        assert401(mintRawJwt(1L, now, now + 600));
    }

    @Test
    void rejectsFractionalIssueTime() {
        long now = clock.instant().getEpochSecond();
        assert401(mintRawJwt("1", now + 0.5D, now + 600));
    }

    @Test
    void rejectsFractionalExpirationTime() {
        long now = clock.instant().getEpochSecond();
        assert401(mintRawJwt("1", now, now + 600.5D));
    }

    @Test
    void acceptsExpirationAtExactClockSkew() {
        long expirationTime = clock.instant().getEpochSecond() - 60;
        VerifiedEndUser user = verifier.verify(mintRawJwt("1", expirationTime - 600, expirationTime),
                fixture.project());

        assertThat(user.userId()).isEqualTo(1L);
    }

    @Test
    void rejectsExpirationOneNanosecondPastClockSkew() {
        Instant exactBoundary = clock.instant();
        long expirationTime = exactBoundary.getEpochSecond() - 60;

        assert401(verifierAt(exactBoundary.plusNanos(1)),
                mintRawJwt("1", expirationTime - 600, expirationTime));
    }

    @Test
    void rejectsExpirationPastClockSkew() {
        long expirationTime = clock.instant().getEpochSecond() - 61;
        assert401(mintRawJwt("1", expirationTime - 600, expirationTime));
    }

    @Test
    void acceptsIssueTimeAtExactClockSkew() {
        long issueTime = clock.instant().getEpochSecond() + 60;
        VerifiedEndUser user = verifier.verify(mintRawJwt("1", issueTime, issueTime + 600), fixture.project());

        assertThat(user.userId()).isEqualTo(1L);
    }

    @Test
    void rejectsIssueTimeOneNanosecondPastClockSkew() {
        Instant exactBoundary = clock.instant();
        long issueTime = exactBoundary.getEpochSecond() + 60;

        assert401(verifierAt(exactBoundary.minusNanos(1)), mintRawJwt("1", issueTime, issueTime + 600));
    }

    @Test
    void rejectsIssueTimePastClockSkew() {
        long issueTime = clock.instant().getEpochSecond() + 61;
        assert401(mintRawJwt("1", issueTime, issueTime + 600));
    }

    @Test
    void acceptsTtlAtExactMaximum() {
        long issueTime = clock.instant().getEpochSecond();
        VerifiedEndUser user = verifier.verify(mintRawJwt("1", issueTime, issueTime + 3600), fixture.project());

        assertThat(user.userId()).isEqualTo(1L);
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
