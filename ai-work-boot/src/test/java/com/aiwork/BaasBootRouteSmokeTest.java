/*
 *
 *      Copyright (c) 2018-2026, lengleng All rights reserved.
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

package com.aiwork;

import com.aiwork.baas.controller.StudioTableController;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * boot profile 对 BaaS 模块的 classpath 与路由装配冒烟测试。
 *
 * @author ai-work
 * @date 2026/07/20
 */
class BaasBootRouteSmokeTest {

    @Test
    void bootClasspathExposesStudioTableRoutes() {
        try (AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(RouteConfiguration.class);
            context.refresh();

            RequestMappingHandlerMapping mappings = context.getBean(RequestMappingHandlerMapping.class);
            assertThat(mappings.getHandlerMethods().entrySet()).anySatisfy(entry -> {
                assertThat(entry.getKey().getPatternValues())
                    .contains("/studio/projects/{ref}/tables");
                assertThat(entry.getValue().getBeanType()).isEqualTo(StudioTableController.class);
            });
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class RouteConfiguration {

        @Bean
        StudioTableController studioTableController() {
            return new StudioTableController(null, null, null);
        }

    }

}
