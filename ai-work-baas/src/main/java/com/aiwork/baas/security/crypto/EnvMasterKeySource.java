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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 生产主密钥来源,只读取系统环境变量。
 *
 * @author ai-work
 * @date 2026/07/17
 */
public class EnvMasterKeySource implements MasterKeySource {

    private static final String MASTER_KEYS_ENV_NAME = "BAAS_MASTER_KEYS";

    private static final String ACTIVE_KEY_ID_ENV_NAME = "BAAS_ACTIVE_KEY_ID";

    private static final String DEFAULT_ACTIVE_KEY_ID = "k1";

    @Override
    public Map<String, String> masterKeys() {
        String masterKeysValue = System.getenv(MASTER_KEYS_ENV_NAME);
        if (masterKeysValue == null || masterKeysValue.isBlank()) {
            throw new IllegalStateException("环境变量 BAAS_MASTER_KEYS 未配置,BaaS 拒绝启动");
        }

        Map<String, String> masterKeys = new LinkedHashMap<>();
        for (String entry : masterKeysValue.split(",", -1)) {
            addMasterKey(masterKeys, entry);
        }
        return Map.copyOf(masterKeys);
    }

    @Override
    public String activeKeyId() {
        String activeKeyId = System.getenv(ACTIVE_KEY_ID_ENV_NAME);
        return activeKeyId == null || activeKeyId.isBlank() ? DEFAULT_ACTIVE_KEY_ID : activeKeyId;
    }

    private void addMasterKey(Map<String, String> masterKeys, String entry) {
        String[] keyPair = entry.split("=", 2);
        if (keyPair.length != 2 || keyPair[0].isBlank() || keyPair[1].isBlank()) {
            throw new IllegalStateException("环境变量 BAAS_MASTER_KEYS 格式非法");
        }

        String keyId = keyPair[0].trim();
        String base64Key = keyPair[1].trim();
        if (masterKeys.putIfAbsent(keyId, base64Key) != null) {
            throw new IllegalStateException("环境变量 BAAS_MASTER_KEYS 包含重复 keyId");
        }
    }

}
