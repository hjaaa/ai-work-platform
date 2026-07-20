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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 双层 DDL 锁配置校验测试。
 *
 * @author ai-work
 * @date 2026/07/18
 */
class DdlLockConfigurationTest {

	private final DdlLockConfiguration configuration = new DdlLockConfiguration();

	@ParameterizedTest
	@CsvSource({ "0, 1", "1000, 0", "1000, 1000", "1000, 2000" })
	void rejectsInvalidTtlAndRenewPeriod(long ttlMillis, long renewPeriodMillis) {
		assertThatThrownBy(() -> configuration.ddlLockManager(new StringRedisTemplate(), ttlMillis,
				renewPeriodMillis, 4))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("DDL_LOCK_CONFIGURATION_INVALID");
	}

}
