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

package com.aiwork.baas.security.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * BaaS 专用加密器(spec §12.1)。AES-256-GCM,密文格式 v1:{keyId}:{base64(iv|ciphertext|tag)}。
 * 不使用仓库默认 Jasypt;主密钥缺失时构造即失败(fail-fast)。
 *
 * @author ai-work
 * @date 2026/07/17
 */
public class BaasCryptoService {

    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";

    private static final String CIPHER_VERSION = "v1";

    private static final String AES_ALGORITHM = "AES";

    private static final int AES_256_KEY_LENGTH = 32;

    private static final int IV_LENGTH = 12;

    private static final int TAG_LENGTH_BYTES = 16;

    private static final int TAG_LENGTH_BITS = 128;

    private static final int CIPHER_TEXT_PART_COUNT = 3;

    private static final Pattern KEY_ID_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    private final Map<String, SecretKey> keys;

    private final String activeKeyId;

    private final SecureRandom secureRandom = new SecureRandom();

    public BaasCryptoService(Map<String, String> base64Keys, String activeKeyId) {
        this.keys = parseMasterKeys(base64Keys);
        validateKeyId(activeKeyId);
        if (!keys.containsKey(activeKeyId)) {
            throw new IllegalStateException("baas master key 未配置或缺少 active key");
        }
        this.activeKeyId = activeKeyId;
    }

    public String encrypt(String plaintext, String aad) {
        requireText(plaintext, "plaintext");
        requireText(aad, "aad");

        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = createCipher(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), iv);
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] encryptedPayload = new byte[IV_LENGTH + encrypted.length];
            System.arraycopy(iv, 0, encryptedPayload, 0, IV_LENGTH);
            System.arraycopy(encrypted, 0, encryptedPayload, IV_LENGTH, encrypted.length);
            return CIPHER_VERSION + ":" + activeKeyId + ":" + Base64.getEncoder().encodeToString(encryptedPayload);
        }
        catch (GeneralSecurityException e) {
            throw new IllegalStateException("encrypt failed", e);
        }
    }

    public String decrypt(String cipherText, String aad) {
        requireCipherText(cipherText);
        requireText(aad, "aad");

        try {
            String[] cipherTextParts = cipherText.split(":", CIPHER_TEXT_PART_COUNT);
            if (cipherTextParts.length != CIPHER_TEXT_PART_COUNT || !CIPHER_VERSION.equals(cipherTextParts[0])) {
                throw new IllegalStateException("unknown cipher version or keyId");
            }

            SecretKey key = keys.get(cipherTextParts[1]);
            if (key == null) {
                throw new IllegalStateException("unknown cipher version or keyId");
            }

            byte[] encryptedPayload = Base64.getDecoder().decode(cipherTextParts[2]);
            if (encryptedPayload.length < IV_LENGTH + TAG_LENGTH_BYTES) {
                throw new IllegalStateException("decrypt failed (AAD mismatch or corrupted)");
            }

            Cipher cipher = createCipher(Cipher.DECRYPT_MODE, key, encryptedPayload);
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] plaintext = cipher.doFinal(encryptedPayload, IV_LENGTH, encryptedPayload.length - IV_LENGTH);
            return new String(plaintext, StandardCharsets.UTF_8);
        }
        catch (IllegalStateException e) {
            throw e;
        }
        catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new IllegalStateException("decrypt failed (AAD mismatch or corrupted)", e);
        }
    }

    private Map<String, SecretKey> parseMasterKeys(Map<String, String> base64Keys) {
        if (base64Keys == null || base64Keys.isEmpty()) {
            throw new IllegalStateException("baas master key 未配置");
        }

        Map<String, SecretKey> parsedKeys = new HashMap<>();
        for (Map.Entry<String, String> keyEntry : base64Keys.entrySet()) {
            String keyId = keyEntry.getKey();
            validateKeyId(keyId);
            if (keyEntry.getValue() == null || keyEntry.getValue().isBlank()) {
                throw new IllegalStateException("baas master key 配置非法");
            }
            parsedKeys.put(keyId, new SecretKeySpec(decodeMasterKey(keyEntry.getValue()), AES_ALGORITHM));
        }
        return Map.copyOf(parsedKeys);
    }

    private byte[] decodeMasterKey(String base64Key) {
        try {
            byte[] rawKey = Base64.getDecoder().decode(base64Key);
            if (rawKey.length != AES_256_KEY_LENGTH) {
                throw new IllegalStateException("baas master key 不是 32 字节");
            }
            return rawKey;
        }
        catch (IllegalArgumentException e) {
            throw new IllegalStateException("baas master key 格式非法", e);
        }
    }

    private Cipher createCipher(int mode, SecretKey key, byte[] iv) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(mode, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv, 0, IV_LENGTH));
        return cipher;
    }

    private void validateKeyId(String keyId) {
        if (keyId == null || !KEY_ID_PATTERN.matcher(keyId).matches()) {
            throw new IllegalStateException("baas master key keyId 非法");
        }
    }

    private void requireCipherText(String cipherText) {
        if (cipherText == null || cipherText.isBlank()) {
            throw new IllegalStateException("decrypt failed (AAD mismatch or corrupted)");
        }
    }

    private void requireText(String value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
    }

}
