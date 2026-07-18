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

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * BaaS 专用加密器生产装配。
 *
 * @author ai-work
 * @date 2026/07/17
 */
@Configuration
public class BaasCryptoConfiguration {

    @Bean
    public MasterKeySource masterKeySource() {
        return new EnvMasterKeySource();
    }

    @Bean
    public BaasCryptoService baasCryptoService(MasterKeySource masterKeySource) {
        return new BaasCryptoService(masterKeySource.masterKeys(), masterKeySource.activeKeyId());
    }

}
