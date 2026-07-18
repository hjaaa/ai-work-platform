/*
 *
 *      Copyright (c) 2018-2025, lengleng All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 * Neither the name of the pig4cloud.com developer nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 * Author: lengleng (wangiegie@gmail.com)
 *
 */

package com.aiwork.baas.security.key;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API Key 生成器测试
 *
 * @author ai-work
 * @date 2026/07/17
 */
class ApiKeyGeneratorTest {

    private final ApiKeyGenerator generator = new ApiKeyGenerator();

    @Test
    void publishableKeyMatchesPublicContract() {
        ApiKeyGenerator.GeneratedKey key = generator.generatePublishable();

        assertGeneratedKey(key, "pub_");
    }

    @Test
    void secretKeyMatchesPublicContract() {
        ApiKeyGenerator.GeneratedKey key = generator.generateSecret();

        assertGeneratedKey(key, "sec_");
    }

    @Test
    void sha256HexMatchesStandardVector() {
        assertThat(generator.sha256Hex("abc"))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void matchesUsesStoredHash() {
        ApiKeyGenerator.GeneratedKey key = generator.generatePublishable();

        assertThat(generator.matches(key.plaintext(), key.hash())).isTrue();
        assertThat(generator.matches(key.plaintext() + "x", key.hash())).isFalse();
        assertThat(generator.matches(key.plaintext(), changeFirstCharacter(key.hash()))).isFalse();
    }

    @Test
    void projectRefIsTwentyLowercaseLetters() {
        String ref = generator.generateProjectRef();

        assertThat(ref).matches("[a-z]{20}");
        assertThat(generator.generateProjectRef()).isNotEqualTo(ref);
    }

    @Test
    void runtimePasswordMatchesPolicy() {
        assertThat(generator.generateRuntimePassword()).matches("[A-Za-z0-9]{32}");
    }

    private void assertGeneratedKey(ApiKeyGenerator.GeneratedKey key, String expectedTypePrefix) {
        assertThat(key.plaintext()).startsWith(expectedTypePrefix).hasSize(47);
        assertThat(Base64.getUrlDecoder().decode(key.plaintext().substring(4))).hasSize(32);
        assertThat(key.hash()).isEqualTo(generator.sha256Hex(key.plaintext()));
        assertThat(key.prefix()).isEqualTo(key.plaintext().substring(0, 12));
    }

    private String changeFirstCharacter(String value) {
        char replacement = value.charAt(0) == '0' ? '1' : '0';
        return replacement + value.substring(1);
    }

}
