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

package com.aiwork.baas.ddl.lock;

import com.aiwork.baas.provision.ProvisionerDataSourceHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 双层 DDL 锁装配。
 *
 * @author ai-work
 * @date 2026/07/18
 */
@Configuration
public class DdlLockConfiguration {

	@Bean
	public DdlLockManager ddlLockManager(StringRedisTemplate stringRedisTemplate,
			@Value("${baas.ddl.lock-ttl-millis:60000}") long ttlMillis,
			@Value("${baas.ddl.lock-renew-millis:20000}") long renewPeriodMillis,
			@Value("${baas.ddl.lock-watchdog-threads:4}") int watchdogThreads) {
		if (ttlMillis <= 0 || renewPeriodMillis <= 0 || renewPeriodMillis >= ttlMillis) {
			throw new IllegalArgumentException("DDL_LOCK_CONFIGURATION_INVALID");
		}
		return new DdlLockManager(stringRedisTemplate, ttlMillis, renewPeriodMillis, watchdogThreads);
	}

	@Bean
	public AdvisoryLockTemplate advisoryLockTemplate(ProvisionerDataSourceHolder holder) {
		return new AdvisoryLockTemplate(holder.dataSource());
	}

	@Bean
	public ProjectDdlLockExecutor projectDdlLockExecutor(DdlLockManager lockManager,
			AdvisoryLockTemplate advisoryLockTemplate) {
		return new ProjectDdlLockExecutor(lockManager, advisoryLockTemplate, LockAcquisitionObserver.NOOP);
	}

}
