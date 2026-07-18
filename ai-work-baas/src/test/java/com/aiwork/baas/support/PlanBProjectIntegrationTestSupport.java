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

package com.aiwork.baas.support;

import com.aiwork.baas.LifecycleTestApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Plan B 项目级 Spring 集成测试共享装配。
 *
 * @author ai-work
 * @date 2026/07/18
 */
@SpringBootTest(classes = LifecycleTestApplication.class,
		properties = { "spring.config.import=", "spring.cloud.nacos.discovery.enabled=false",
				"spring.cloud.nacos.config.enabled=false",
				"spring.cloud.service-registry.auto-registration.enabled=false" })
public abstract class PlanBProjectIntegrationTestSupport extends PlanBContainerSupport {

	@DynamicPropertySource
	protected static void registerPlanBProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", () -> MYSQL_USERNAME);
		registry.add("spring.datasource.password", () -> MYSQL_PASSWORD);
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
		registry.add("baas.provisioner.url", () -> MYSQL.getJdbcUrl().replace("/ai_work_baas", "/mysql"));
		registry.add("baas.provisioner.username", () -> MYSQL_USERNAME);
		registry.add("baas.provisioner.password", () -> MYSQL_PASSWORD);
		registry.add("baas.project-db.host", MYSQL::getHost);
		registry.add("baas.project-db.port", () -> MYSQL.getMappedPort(3306));
		registry.add("server.servlet.context-path", () -> "");
	}

}
