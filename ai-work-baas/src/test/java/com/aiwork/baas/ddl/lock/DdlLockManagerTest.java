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

import com.aiwork.baas.support.PlanBContainerSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Redis DDL 锁集成测试。
 *
 * @author ai-work
 * @date 2026/07/18
 */
class DdlLockManagerTest extends PlanBContainerSupport {

	private static LettuceConnectionFactory connectionFactory;

	private static StringRedisTemplate redisTemplate;

	private final List<DdlLockManager> managers = new ArrayList<>();

	@BeforeAll
	static void setUpRedis() {
		connectionFactory = redisConnectionFactory();
		redisTemplate = redisTemplate(connectionFactory);
	}

	@AfterAll
	static void tearDownRedis() {
		connectionFactory.destroy();
	}

	@AfterEach
	void shutdownManagers() {
		managers.forEach(DdlLockManager::shutdown);
	}

	@Test
	void mutualExclusionAndRelease() {
		DdlLockManager manager = manager(60000, 20000);
		LockHandle first = manager.tryAcquire(101L);
		assertThat(first).isNotNull();
		assertThat(manager.tryAcquire(101L)).isNull();
		assertThat(manager.isHeldBy(101L, first.ownerToken())).isTrue();

		manager.release(first);
		LockHandle second = manager.tryAcquire(101L);
		assertThat(second).isNotNull();
		assertThat(second.ownerToken()).isNotEqualTo(first.ownerToken());
		manager.release(second);
	}

	@Test
	void watchdogKeepsLockBeyondTtl() {
		DdlLockManager manager = manager(500, 100);
		LockHandle handle = manager.tryAcquire(102L);
		assertThat(handle).isNotNull();

		await().pollDelay(1500, MILLISECONDS).atMost(2500, MILLISECONDS).until(() -> manager.stillHeld(handle));
		manager.release(handle);
	}

	@Test
	void stalledWatchdogLosesLockAndOthersCanAcquire() {
		DdlLockManager manager = manager(300, 60000);
		LockHandle handle = manager.tryAcquire(103L);
		assertThat(handle).isNotNull();

		await().atMost(3, SECONDS).until(() -> !manager.stillHeld(handle));
		LockHandle successor = manager.tryAcquire(103L);
		assertThat(successor).isNotNull();
		assertThat(manager.isHeldBy(103L, handle.ownerToken())).isFalse();
		manager.release(successor);
	}

	@Test
	void releaseIsCompareAndDeleteOnOwnerToken() {
		DdlLockManager manager = manager(300, 60000);
		LockHandle stale = manager.tryAcquire(104L);
		assertThat(stale).isNotNull();
		await().atMost(3, SECONDS).until(() -> redisTemplate.opsForValue().get(DdlLockManager.lockKey(104L)) == null);

		LockHandle successor = manager.tryAcquire(104L);
		assertThat(successor).isNotNull();
		manager.release(stale);
		assertThat(redisTemplate.opsForValue().get(DdlLockManager.lockKey(104L))).isEqualTo(successor.ownerToken());
		manager.release(successor);
	}

	@Test
	void renewFailureAfterKeyHijackMarksLost() {
		DdlLockManager manager = manager(500, 100);
		LockHandle handle = manager.tryAcquire(105L);
		assertThat(handle).isNotNull();
		redisTemplate.opsForValue().set(DdlLockManager.lockKey(105L), "other-owner");

		await().atMost(3, SECONDS).until(handle::lost);
		assertThat(manager.stillHeld(handle)).isFalse();
		redisTemplate.delete(DdlLockManager.lockKey(105L));
	}

	@Test
	void watchdogSchedulingFailureCleansAcquiredToken() {
		DdlLockManager manager = manager(60000, 20000);
		manager.shutdown();

		assertThatThrownBy(() -> manager.tryAcquire(106L))
			.isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
		assertThat(redisTemplate.opsForValue().get(DdlLockManager.lockKey(106L))).isNull();
	}

	private DdlLockManager manager(long ttlMillis, long renewPeriodMillis) {
		DdlLockManager manager = new DdlLockManager(redisTemplate, ttlMillis, renewPeriodMillis);
		managers.add(manager);
		return manager;
	}

}
