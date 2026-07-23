package com.aiwork.baas.data.enduser;

import com.aiwork.baas.entity.BaasJwtKey;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.enums.JwtKeyStatus;
import com.aiwork.baas.security.crypto.BaasCryptoService;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BaasJwtSigner 与 emergencyRotate 竞态处理(spec §6.1/§7.6):签发以 FOR SHARE 锁定读密钥,
 * 与轮换的 FOR UPDATE 互斥同步,从锁定读的密钥集中选 CURRENT 签发,不用已撤销 kid 出生即死。
 */
class BaasJwtSignerTest {

    private final com.aiwork.baas.mapper.BaasJwtKeyMapper jwtKeyMapper =
            mock(com.aiwork.baas.mapper.BaasJwtKeyMapper.class);

    private final BaasCryptoService cryptoService = mock(BaasCryptoService.class);

    private final AuthProperties properties = new AuthProperties();

    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);

    private final BaasJwtSigner signer = new BaasJwtSigner(jwtKeyMapper, cryptoService, properties,
            Clock.fixed(Instant.parse("2026-07-23T00:00:00Z"), ZoneOffset.UTC), transactionTemplate);

    {
        // mock 事务模板:直接同步执行回调,等价于在事务内运行
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
        });
    }

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
    void signsWithCurrentKeyFromLockingRead() throws Exception {
        when(cryptoService.decrypt(anyString(), anyString()))
            .thenReturn(Base64.getEncoder().encodeToString(new byte[32]));
        // 锁定读返回轮换后的密钥集(旧 REVOKED + 新 CURRENT):签发器只取 CURRENT,不用已撤销的旧 kid
        when(jwtKeyMapper.selectByProjectForShare(anyLong()))
            .thenReturn(List.of(key("kid-old", JwtKeyStatus.REVOKED), key("kid-new", JwtKeyStatus.CURRENT)));

        BaasJwtSigner.SignedAccessToken token = signer.sign(project(), 42L, 7L);
        assertThat(signedKid(token)).isEqualTo("kid-new");
    }

    @Test
    void signsWithSingleCurrentKey() throws Exception {
        when(cryptoService.decrypt(anyString(), anyString()))
            .thenReturn(Base64.getEncoder().encodeToString(new byte[32]));
        when(jwtKeyMapper.selectByProjectForShare(anyLong()))
            .thenReturn(List.of(key("kid-stable", JwtKeyStatus.CURRENT)));

        BaasJwtSigner.SignedAccessToken token = signer.sign(project(), 42L, 7L);
        assertThat(signedKid(token)).isEqualTo("kid-stable");
    }

    @Test
    void throwsWhenNoCurrentKey() {
        // 仅有非 CURRENT 密钥(如全部被紧急撤销、CURRENT 尚未可见)时不签发
        when(jwtKeyMapper.selectByProjectForShare(anyLong()))
            .thenReturn(List.of(key("kid-revoked", JwtKeyStatus.REVOKED)));
        assertThatThrownBy(() -> signer.sign(project(), 42L, 7L))
            .isInstanceOf(com.aiwork.baas.data.error.DataApiException.class);
    }
}
