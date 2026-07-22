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
import com.aiwork.baas.service.EndUserAdminService;
import com.aiwork.baas.service.JwtKeyRotationService;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

import java.util.Base64;
import java.util.Map;

/**
 * 生命周期集成测试专用装配，仅加载生命周期直接依赖并提供确定的测试加密 Bean。
 * 排除 EndUserAdminService/JwtKeyRotationService：二者依赖 com.aiwork.baas.data.enduser
 * 包下未纳入扫描的 EndUserStore/AuthProperties bean，且生命周期测试不使用终端用户鉴权能力。
 * TypeExcludeFilter/AutoConfigurationExcludeFilter 显式保留：@SpringBootApplication 不支持
 * excludeFilters 属性，改用 @ComponentScan 时须手动带上这两个默认过滤器，
 * 否则测试类中的内嵌 @Configuration（如 PhysicalPreconditionsHealthIndicatorTest 的
 * FailingPhysicalPreconditionsConfiguration）会被误扫描导致 bean 定义冲突。
 *
 * @author ai-work
 * @date 2026/07/17
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = { "com.aiwork.baas.service", "com.aiwork.baas.provision",
        "com.aiwork.baas.datasource", "com.aiwork.baas.security.key", "com.aiwork.baas.ddl" },
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
                @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                        classes = { EndUserAdminService.class, JwtKeyRotationService.class }) })
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
