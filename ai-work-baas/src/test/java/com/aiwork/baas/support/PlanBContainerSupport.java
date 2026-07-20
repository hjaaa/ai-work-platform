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

package com.aiwork.baas.support;

import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;

import java.util.stream.Stream;

/**
 * Plan B 集成测试共享 MySQL 与 Redis 容器。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public abstract class PlanBContainerSupport {

	protected static final String MYSQL_USERNAME = "root";

	protected static final String MYSQL_PASSWORD = "root";

	protected static final int REDIS_PORT = 6379;

	protected static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withUsername(MYSQL_USERNAME)
		.withPassword(MYSQL_PASSWORD)
		.withDatabaseName("ai_work_baas")
		.withInitScript("init-metadata.sql");

	protected static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
		.withExposedPorts(REDIS_PORT);

	static {
		Startables.deepStart(Stream.of(MYSQL, REDIS)).join();
	}

	protected static DriverManagerDataSource mysqlDataSource() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL_USERNAME,
				MYSQL_PASSWORD);
		dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
		return dataSource;
	}

	protected static LettuceConnectionFactory redisConnectionFactory() {
		LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(REDIS.getHost(),
				REDIS.getMappedPort(REDIS_PORT));
		connectionFactory.afterPropertiesSet();
		return connectionFactory;
	}

	protected static StringRedisTemplate redisTemplate(LettuceConnectionFactory connectionFactory) {
		StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
		redisTemplate.afterPropertiesSet();
		return redisTemplate;
	}

}
