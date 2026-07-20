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

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 双层项目 DDL 锁集成测试。
 *
 * @author ai-work
 * @date 2026/07/18
 */
class ProjectDdlLockExecutorTest extends PlanBContainerSupport {

	private static LettuceConnectionFactory redisConnectionFactory;

	private static StringRedisTemplate redisTemplate;

	private static AdvisoryLockTemplate advisoryLockTemplate;

	private final List<DdlLockManager> managers = new ArrayList<>();

	@BeforeAll
	static void setUpInfrastructure() {
		redisConnectionFactory = redisConnectionFactory();
		redisTemplate = redisTemplate(redisConnectionFactory);
		advisoryLockTemplate = new AdvisoryLockTemplate(mysqlDataSource());
	}

	@AfterAll
	static void tearDownInfrastructure() {
		redisConnectionFactory.destroy();
	}

	@AfterEach
	void shutdownManagers() {
		managers.forEach(DdlLockManager::shutdown);
	}

	@Test
	void callbackRunsOnlyAfterRedisThenAdvisoryAndRedisRecheck() {
		DdlLockManager manager = manager();
		LockAcquisitionObserver observer = handle -> {
			assertThat(manager.stillHeld(handle)).isTrue();
			assertThat(queryLockState("SELECT IS_FREE_LOCK(?)", AdvisoryLockTemplate.lockName(301L))).isEqualTo(1L);
		};
		ProjectDdlLockExecutor executor = new ProjectDdlLockExecutor(manager, advisoryLockTemplate, observer);

		String token = executor.execute(301L, (handle, connection) -> {
			assertThat(manager.stillHeld(handle)).isTrue();
			assertThat(queryLong(connection, "SELECT IS_USED_LOCK(?)", AdvisoryLockTemplate.lockName(301L)))
				.isEqualTo(queryLong(connection, "SELECT CONNECTION_ID()", null));
			return handle.ownerToken();
		});

		assertThat(token).isNotBlank();
		assertThat(redisTemplate.opsForValue().get(DdlLockManager.lockKey(301L))).isNull();
	}

	@Test
	void pausedBeforeGetLockCannotRunAfterLosingRedisOwnership() throws Exception {
		CountDownLatch aPaused = new CountDownLatch(1);
		CountDownLatch resumeA = new CountDownLatch(1);
		AtomicInteger aCallbacks = new AtomicInteger();
		AtomicReference<Throwable> aFailure = new AtomicReference<>();
		DdlLockManager managerA = manager();
		DdlLockManager managerB = manager();
		ProjectDdlLockExecutor executorA = new ProjectDdlLockExecutor(managerA, advisoryLockTemplate, handle -> {
			aPaused.countDown();
			try {
				if (!resumeA.await(20, TimeUnit.SECONDS)) {
					throw new IllegalStateException("test latch timeout");
				}
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(exception);
			}
		});
		ProjectDdlLockExecutor executorB = new ProjectDdlLockExecutor(managerB, advisoryLockTemplate,
				LockAcquisitionObserver.NOOP);
		Thread threadA = new Thread(() -> {
			try {
				executorA.execute(302L, (handle, connection) -> {
					aCallbacks.incrementAndGet();
					return null;
				});
			}
			catch (Throwable throwable) {
				aFailure.set(throwable);
			}
		}, "ddl-lock-stale-owner-test");
		try {
			threadA.start();
			assertThat(aPaused.await(10, TimeUnit.SECONDS)).isTrue();
			redisTemplate.delete(DdlLockManager.lockKey(302L));
			assertThat(executorB.<String>execute(302L, (handle, connection) -> "b")).isEqualTo("b");
			resumeA.countDown();
			threadA.join(20000);

			assertThat(aCallbacks).hasValue(0);
			assertThat(aFailure.get()).isInstanceOf(DdlLockBusyException.class).hasMessageContaining("owner_token 已失效");
		}
		finally {
			resumeA.countDown();
			threadA.join(20000);
		}
	}

	@Test
	void postCallbackRecheckRejectsLostTokenAndPreservesSuccessor() {
		DdlLockManager manager = manager();
		ProjectDdlLockExecutor executor = new ProjectDdlLockExecutor(manager, advisoryLockTemplate,
				LockAcquisitionObserver.NOOP);

		assertThatThrownBy(() -> executor.execute(303L, (handle, connection) -> {
			redisTemplate.opsForValue().set(DdlLockManager.lockKey(303L), "successor-token");
			return "must-not-return";
		})).isInstanceOf(DdlLockBusyException.class).hasMessageContaining("owner_token 已失效");
		assertThat(redisTemplate.opsForValue().get(DdlLockManager.lockKey(303L))).isEqualTo("successor-token");
		redisTemplate.delete(DdlLockManager.lockKey(303L));
	}

	@Test
	void advisoryAcquireFailureReleasesRedisToken() throws Exception {
		DdlLockManager manager = manager();
		ProjectDdlLockExecutor executor = new ProjectDdlLockExecutor(manager, advisoryLockTemplate,
				LockAcquisitionObserver.NOOP);
		try (Connection holder = mysqlDataSource().getConnection()) {
			assertThat(queryLong(holder, "SELECT GET_LOCK(?, 0)", AdvisoryLockTemplate.lockName(304L))).isEqualTo(1L);

			assertThatThrownBy(() -> executor.execute(304L, (handle, connection) -> null))
				.isInstanceOf(DdlLockBusyException.class);
			assertThat(redisTemplate.opsForValue().get(DdlLockManager.lockKey(304L))).isNull();
			queryLong(holder, "SELECT RELEASE_LOCK(?)", AdvisoryLockTemplate.lockName(304L));
		}
	}

	@Test
	void observerFailureReleasesRedisWithoutEnteringAdvisory() {
		DdlLockManager manager = manager();
		ProjectDdlLockExecutor executor = new ProjectDdlLockExecutor(manager, advisoryLockTemplate, handle -> {
			throw new IllegalStateException("observer failed");
		});

		assertThatThrownBy(() -> executor.execute(305L, (handle, connection) -> null))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("observer failed");
		assertThat(redisTemplate.opsForValue().get(DdlLockManager.lockKey(305L))).isNull();
		assertThat(queryLockState("SELECT IS_FREE_LOCK(?)", AdvisoryLockTemplate.lockName(305L))).isEqualTo(1L);
	}

	@Test
	void expiredRedisLeaseCannotBypassOldExecutorsAdvisoryLock() throws Exception {
		CountDownLatch oldInside = new CountDownLatch(1);
		CountDownLatch releaseOld = new CountDownLatch(1);
		AtomicReference<Throwable> oldFailure = new AtomicReference<>();
		DdlLockManager oldManager = new DdlLockManager(redisTemplate, 300, 100);
		DdlLockManager successorManager = manager();
		managers.add(oldManager);
		ProjectDdlLockExecutor oldExecutor = new ProjectDdlLockExecutor(oldManager, advisoryLockTemplate,
				LockAcquisitionObserver.NOOP);
		ProjectDdlLockExecutor successor = new ProjectDdlLockExecutor(successorManager, advisoryLockTemplate,
				LockAcquisitionObserver.NOOP);
		Thread oldThread = new Thread(() -> {
			try {
				oldExecutor.execute(306L, (handle, connection) -> {
					handle.renewTask.cancel(false);
					oldInside.countDown();
					releaseOld.await(10, TimeUnit.SECONDS);
					return null;
				});
			}
			catch (Throwable throwable) {
				oldFailure.set(throwable);
			}
		});
		try {
			oldThread.start();
			assertThat(oldInside.await(10, TimeUnit.SECONDS)).isTrue();
			Thread.sleep(600);

			assertThatThrownBy(() -> successor.execute(306L, (handle, connection) -> "must-not-run"))
				.isInstanceOf(DdlLockBusyException.class);
			assertThat(redisTemplate.opsForValue().get(DdlLockManager.lockKey(306L))).isNull();
		}
		finally {
			releaseOld.countDown();
			oldThread.join(10000);
		}
		assertThat(oldFailure.get()).isInstanceOf(DdlLockBusyException.class);
	}

	private DdlLockManager manager() {
		DdlLockManager manager = new DdlLockManager(redisTemplate, 60000, 20000);
		managers.add(manager);
		return manager;
	}

	private static long queryLockState(String sql, String lockName) {
		try (Connection connection = mysqlDataSource().getConnection()) {
			return queryLong(connection, sql, lockName);
		}
		catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static long queryLong(Connection connection, String sql, String parameter) throws Exception {
		try (var statement = connection.prepareStatement(sql)) {
			if (parameter != null) {
				statement.setString(1, parameter);
			}
			try (var resultSet = statement.executeQuery()) {
				assertThat(resultSet.next()).isTrue();
				return resultSet.getLong(1);
			}
		}
	}

}
