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

package com.aiwork.baas;

import com.aiwork.baas.security.CurrentUserProvider;
import com.aiwork.baas.security.TestCurrentUserProvider;
import com.aiwork.baas.security.crypto.BaasCryptoService;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.Base64;
import java.util.Map;

/**
 * 生命周期集成测试专用装配，仅加载生命周期直接依赖并提供确定的测试加密 Bean。
 *
 * @author ai-work
 * @date 2026/07/17
 */
@SpringBootApplication(scanBasePackages = { "com.aiwork.baas.service", "com.aiwork.baas.provision",
        "com.aiwork.baas.datasource", "com.aiwork.baas.security.key", "com.aiwork.baas.ddl" })
@MapperScan("com.aiwork.baas.mapper")
public class LifecycleTestApplication {

    @Bean
    public BaasCryptoService baasCryptoService() {
        return new BaasCryptoService(Map.of("k1", Base64.getEncoder().encodeToString(new byte[32])), "k1");
    }

    @Bean
    public CurrentUserProvider currentUserProvider() {
        return new TestCurrentUserProvider();
    }

}
