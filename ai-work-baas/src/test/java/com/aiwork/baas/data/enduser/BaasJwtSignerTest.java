package com.aiwork.baas.data.enduser;

import com.aiwork.baas.entity.BaasJwtKey;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.enums.JwtKeyStatus;
import com.aiwork.baas.security.crypto.BaasCryptoService;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BaasJwtSigner 与 emergencyRotate 竞态处理(spec §6.1/§7.6):签名期间 CURRENT 密钥被紧急轮换为
 * REVOKED 时,签名器复查发现 kid 已变,用新 CURRENT 重签,避免返回 200 但 access token 出生即死。
 */
class BaasJwtSignerTest {

    private final com.aiwork.baas.mapper.BaasJwtKeyMapper jwtKeyMapper =
            mock(com.aiwork.baas.mapper.BaasJwtKeyMapper.class);

    private final BaasCryptoService cryptoService = mock(BaasCryptoService.class);

    private final AuthProperties properties = new AuthProperties();

    private final BaasJwtSigner signer = new BaasJwtSigner(jwtKeyMapper, cryptoService, properties,
            Clock.fixed(Instant.parse("2026-07-23T00:00:00Z"), ZoneOffset.UTC));

    private BaasProject project() {
        BaasProject project = mock(BaasProject.class);
        when(project.getId()).thenReturn(1L);
        when(project.getProjectRef()).thenReturn("ref0001");
        return project;
    }

    private BaasJwtKey key(String kid, JwtKeyStatus status) {
        BaasJwtKey key = new BaasJwtKey();
        key.setProjectId(1L);
        key.setKid(kid);
        key.setStatus(status);
        key.setSecretCipher("cipher-" + kid);
        return key;
    }

    private String signedKid(BaasJwtSigner.SignedAccessToken token) throws Exception {
        return SignedJWT.parse(token.token()).getHeader().getKeyID();
    }

    @Test
    void reSignsWithNewCurrentKeyWhenRotatedMidSign() throws Exception {
        when(cryptoService.decrypt(anyString(), anyString()))
            .thenReturn(Base64.getEncoder().encodeToString(new byte[32]));
        BaasJwtKey oldKey = key("kid-old", JwtKeyStatus.CURRENT);
        BaasJwtKey newKey = key("kid-new", JwtKeyStatus.CURRENT);
        // 序列:①初选=old ②签后复查=new(轮换发生)③重试初选=new ④重试复查=new(稳定)
        when(jwtKeyMapper.selectOne(any())).thenReturn(oldKey, newKey, newKey, newKey);

        BaasJwtSigner.SignedAccessToken token = signer.sign(project(), 42L, 7L);
        // 最终 token 必须用轮换后的新 CURRENT kid 签发,而非出生即死的 old kid
        assertThat(signedKid(token)).isEqualTo("kid-new");
    }

    @Test
    void signsWithCurrentKidWhenNoRotation() throws Exception {
        when(cryptoService.decrypt(anyString(), anyString()))
            .thenReturn(Base64.getEncoder().encodeToString(new byte[32]));
        BaasJwtKey current = key("kid-stable", JwtKeyStatus.CURRENT);
        when(jwtKeyMapper.selectOne(any())).thenReturn(current);

        BaasJwtSigner.SignedAccessToken token = signer.sign(project(), 42L, 7L);
        assertThat(signedKid(token)).isEqualTo("kid-stable");
    }

    @Test
    void throwsWhenNoCurrentKey() {
        when(jwtKeyMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> signer.sign(project(), 42L, 7L))
            .isInstanceOf(com.aiwork.baas.data.error.DataApiException.class);
    }
}
