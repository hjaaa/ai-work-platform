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

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

/**
 * opaque API Key、projectRef、Runtime 密码生成(spec §8.1、§12.1)。
 * Key 为 256bit 高熵随机串,存储只留 SHA-256 摘要;比较走常量时间。
 *
 * @author ai-work
 * @date 2026/07/17
 */
@Component
public class ApiKeyGenerator {

    private static final String PUBLISHABLE_KEY_PREFIX = "pub_";

    private static final String SECRET_KEY_PREFIX = "sec_";

    private static final String SHA_256_ALGORITHM = "SHA-256";

    private static final int KEY_RANDOM_BYTE_LENGTH = 32;

    private static final int KEY_PREFIX_LENGTH = 12;

    private static final int PROJECT_REF_LENGTH = 20;

    private static final int RUNTIME_PASSWORD_LENGTH = 32;

    private static final char[] REF_ALPHABET = "abcdefghijklmnopqrstuvwxyz".toCharArray();

    private static final char[] PASSWORD_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        .toCharArray();

    private final SecureRandom secureRandom = new SecureRandom();

    public record GeneratedKey(String plaintext, String hash, String prefix) {
    }

    public GeneratedKey generatePublishable() {
        return generate(PUBLISHABLE_KEY_PREFIX);
    }

    public GeneratedKey generateSecret() {
        return generate(SECRET_KEY_PREFIX);
    }

    public String sha256Hex(String plaintext) {
        Objects.requireNonNull(plaintext, "plaintext must not be null");
        try {
            byte[] digest = MessageDigest.getInstance(SHA_256_ALGORITHM).digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    public boolean matches(String plaintext, String storedHash) {
        if (plaintext == null || storedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(sha256Hex(plaintext).getBytes(StandardCharsets.UTF_8),
            storedHash.getBytes(StandardCharsets.UTF_8));
    }

    public String generateProjectRef() {
        return randomString(REF_ALPHABET, PROJECT_REF_LENGTH);
    }

    public String generateRuntimePassword() {
        return randomString(PASSWORD_ALPHABET, RUNTIME_PASSWORD_LENGTH);
    }

    private GeneratedKey generate(String keyTypePrefix) {
        byte[] randomBytes = new byte[KEY_RANDOM_BYTE_LENGTH];
        secureRandom.nextBytes(randomBytes);
        String plaintext = keyTypePrefix + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        return new GeneratedKey(plaintext, sha256Hex(plaintext), plaintext.substring(0, KEY_PREFIX_LENGTH));
    }

    private String randomString(char[] alphabet, int length) {
        StringBuilder valueBuilder = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            valueBuilder.append(alphabet[secureRandom.nextInt(alphabet.length)]);
        }
        return valueBuilder.toString();
    }

}
