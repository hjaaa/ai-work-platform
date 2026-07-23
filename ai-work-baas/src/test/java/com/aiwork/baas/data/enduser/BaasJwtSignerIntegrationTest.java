package com.aiwork.baas.data.enduser;

import com.aiwork.baas.data.auth.BaasJwtVerifier;
import com.aiwork.baas.data.auth.VerifiedEndUser;
import com.aiwork.baas.data.error.DataApiException;
import com.aiwork.baas.entity.BaasJwtKey;
import com.aiwork.baas.entity.enums.JwtKeyStatus;
import com.aiwork.baas.support.DataPlaneIntegrationTestSupport;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * access JWT 签发(spec §7.6):claims 固定、kid 进 header、密钥无缓存直查。
 */
class BaasJwtSignerIntegrationTest extends DataPlaneIntegrationTestSupport {

    @Autowired
    BaasJwtSigner signer;

    @Autowired
    BaasJwtVerifier verifier;

    @Test
    void signedTokenRoundTripsThroughVerifier() throws Exception {
        BaasJwtSigner.SignedAccessToken token = signer.sign(fixture.project(), 42L, 7L);
        assertThat(token.expiresInSeconds()).isEqualTo(3600L);

        VerifiedEndUser user = verifier.verify(token.token(), fixture.project());
        assertThat(user.userId()).isEqualTo(42L);
        assertThat(user.sessionId()).isEqualTo(7L);

        SignedJWT jwt = SignedJWT.parse(token.token());
        assertThat(jwt.getHeader().getKeyID()).isEqualTo(currentJwtKey().getKid());
        assertThat(jwt.getJWTClaimsSet().getIssuer())
            .isEqualTo("baas/" + fixture.project().getProjectRef());
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("42");
        assertThat(jwt.getJWTClaimsSet().getClaim("role")).isEqualTo("authenticated");
        // session_id 线协议:JSON number(§7.6)
        assertThat(jwt.getJWTClaimsSet().getClaim("session_id")).isEqualTo(7L);
    }

    /** 签发无缓存直查:CURRENT 密钥被撤销后,下一次签发立即失败(§7.6 与紧急轮换对称)。 */
    @Test
    void signerQueriesKeyPerCall() {
        signer.sign(fixture.project(), 1L, 1L);
        jwtKeyMapper.update(null, Wrappers.<BaasJwtKey>lambdaUpdate()
            .eq(BaasJwtKey::getProjectId, fixture.project().getId())
            .set(BaasJwtKey::getStatus, JwtKeyStatus.REVOKED));
        assertThatThrownBy(() -> signer.sign(fixture.project(), 1L, 1L))
            .isInstanceOf(DataApiException.class);
    }

}
