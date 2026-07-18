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

package com.aiwork.baas.ddl.lock;

import com.aiwork.baas.support.PlanBContainerSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MySQL advisory lock 集成测试。
 *
 * @author ai-work
 * @date 2026/07/18
 */
class AdvisoryLockTemplateTest extends PlanBContainerSupport {

	private static AdvisoryLockTemplate template;

	@BeforeAll
	static void setUpTemplate() {
		template = new AdvisoryLockTemplate(mysqlDataSource());
	}

	@Test
	void callbackUsesThePhysicalConnectionThatOwnsTheLock() {
		Long connectionId = template.executeWithLock(201L, connection -> {
			long currentConnectionId = queryLong(connection, "SELECT CONNECTION_ID()");
			long lockOwnerConnectionId = queryLong(connection, "SELECT IS_USED_LOCK('baas_ddl_201')");
			assertThat(lockOwnerConnectionId).isEqualTo(currentConnectionId);
			return currentConnectionId;
		});

		assertThat(connectionId).isPositive();
		assertThat(template.<String>executeWithLock(201L, connection -> "again")).isEqualTo("again");
	}

	@Test
	void secondHolderRejectedWhileLockHeld() throws Exception {
		CountDownLatch acquired = new CountDownLatch(1);
		CountDownLatch releaseSignal = new CountDownLatch(1);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<?> holder = executor.submit(() -> template.executeWithLock(202L, connection -> {
				acquired.countDown();
				releaseSignal.await(10, TimeUnit.SECONDS);
				return null;
			}));
			assertThat(acquired.await(10, TimeUnit.SECONDS)).isTrue();

			assertThatThrownBy(() -> template.executeWithLock(202L, connection -> null))
				.isInstanceOf(DdlLockBusyException.class);

			releaseSignal.countDown();
			holder.get(10, TimeUnit.SECONDS);
			assertThat(template.<String>executeWithLock(202L, connection -> "free")).isEqualTo("free");
		}
		finally {
			releaseSignal.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	void runtimeExceptionsPropagateAndLockIsReleased() {
		assertThatThrownBy(() -> template.executeWithLock(203L, connection -> {
			throw new IllegalArgumentException("boom");
		})).isInstanceOf(IllegalArgumentException.class);

		assertThat(template.<String>executeWithLock(203L, connection -> "ok")).isEqualTo("ok");
	}

	@Test
	void checkedCallbackFailureDoesNotExposeRawCauseAndLockIsReleased() {
		assertThatThrownBy(() -> template.executeWithLock(204L, connection -> {
			throw new Exception("secret jdbc metadata");
		})).isInstanceOf(IllegalStateException.class).hasMessage("DDL_LOCK_CALLBACK_FAILED").hasNoCause();

		assertThat(template.<String>executeWithLock(204L, connection -> "ok")).isEqualTo("ok");
	}

	private static long queryLong(Connection connection, String sql) throws Exception {
		try (var statement = connection.createStatement(); var resultSet = statement.executeQuery(sql)) {
			assertThat(resultSet.next()).isTrue();
			return resultSet.getLong(1);
		}
	}

}
