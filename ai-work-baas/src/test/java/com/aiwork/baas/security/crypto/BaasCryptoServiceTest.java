package com.aiwork.baas.security.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BaaS 专用加密器测试
 *
 * @author ai-work
 * @date 2026/07/17
 */
class BaasCryptoServiceTest {

    private BaasCryptoService cryptoService;

    private static String zeroKey() {
        return Base64.getEncoder().encodeToString(new byte[32]);
    }

    @BeforeEach
    void setUp() {
        cryptoService = new BaasCryptoService(Map.of("k1", zeroKey()), "k1");
    }

    @Test
    void encryptThenDecryptReturnsPlaintext() {
        String cipher = cryptoService.encrypt("secret-value", "1:db_password:1");

        assertThat(cipher).startsWith("v1:k1:");
        assertThat(cryptoService.decrypt(cipher, "1:db_password:1")).isEqualTo("secret-value");
    }

    @Test
    void sameInputProducesDifferentCiphertext() {
        String firstCipher = cryptoService.encrypt("x", "1:jwt_secret:1");
        String secondCipher = cryptoService.encrypt("x", "1:jwt_secret:1");

        assertThat(firstCipher).isNotEqualTo(secondCipher);
    }

    @Test
    void decryptWithWrongAadFails() {
        String cipher = cryptoService.encrypt("secret-value", "1:db_password:1");

        assertThatThrownBy(() -> cryptoService.decrypt(cipher, "2:db_password:1"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingMasterKeyFailsFast() {
        assertThatThrownBy(() -> new BaasCryptoService(Map.of(), "k1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("master key");
    }

    @Test
    void masterKeyIdContainingColonFailsFast() {
        assertThatThrownBy(() -> new BaasCryptoService(Map.of("k:1", zeroKey()), "k:1"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decryptRoutesByKeyIdPrefix() {
        String key2 = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        BaasCryptoService writer = new BaasCryptoService(Map.of("k1", zeroKey(), "k2", key2), "k2");
        String cipher = writer.encrypt("v", "a:b:c");
        BaasCryptoService reader = new BaasCryptoService(Map.of("k1", zeroKey(), "k2", key2), "k1");

        assertThat(cipher).startsWith("v1:k2:");
        assertThat(reader.decrypt(cipher, "a:b:c")).isEqualTo("v");
    }

    @Test
    void envSourceFailsFastWithoutEnvVariable() {
        // CI/本机不应设置 BAAS_MASTER_KEYS;若设置则跳过本用例
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("BAAS_MASTER_KEYS") == null);

        assertThatThrownBy(() -> new EnvMasterKeySource().masterKeys())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("BAAS_MASTER_KEYS");
    }

    @Test
    void decryptWithNullCipherTextFailsWithIllegalStateException() {
        assertThatThrownBy(() -> cryptoService.decrypt(null, "1:db_password:1"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decryptWithShortPayloadFailsWithIllegalStateException() {
        String cipher = "v1:k1:" + Base64.getEncoder().encodeToString(new byte[12]);

        assertThatThrownBy(() -> cryptoService.decrypt(cipher, "1:db_password:1"))
            .isInstanceOf(IllegalStateException.class);
    }

}
