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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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

	@Test
	void callbackErrorStillRestoresSessionAndReleasesLock() {
		assertThatThrownBy(() -> template.executeWithLock(2041L, connection -> {
			throw new AssertionError("simulated process failure");
		})).isInstanceOf(AssertionError.class).hasMessage("simulated process failure");

		assertThat(template.<String>executeWithLock(2041L, connection -> "ok")).isEqualTo("ok");
	}

	@Test
	void callbackAndConnectionCloseFailuresDoNotExposeCauseOrSuppressedExceptions() throws Exception {
		AdvisoryLockTemplate closeFailingTemplate = new AdvisoryLockTemplate(closeFailingDataSource());

		assertThatThrownBy(() -> closeFailingTemplate.executeWithLock(205L, connection -> {
			throw new Exception("secret callback metadata");
		})).isInstanceOf(DdlLockInfrastructureException.class)
			.hasMessage("DDL_LOCK_INFRASTRUCTURE_FAILED")
			.hasNoCause()
			.satisfies(exception -> {
				assertThat(exception.getSuppressed()).isEmpty();
				assertThat(((DdlLockInfrastructureException) exception).advisoryStateUncertain()).isFalse();
			});
	}

	@Test
	void interruptedCallbackRestoresInterruptFlagAndUsesStableError() {
		try {
			assertThatThrownBy(() -> template.executeWithLock(206L, connection -> {
				throw new InterruptedException("secret interruption detail");
			})).isInstanceOf(IllegalStateException.class)
				.hasMessage("DDL_LOCK_CALLBACK_INTERRUPTED")
				.hasNoCause()
				.satisfies(exception -> assertThat(exception.getSuppressed()).isEmpty());
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		}
		finally {
			Thread.interrupted();
		}
		assertThat(template.<String>executeWithLock(206L, connection -> "released")).isEqualTo("released");
	}

	private static long queryLong(Connection connection, String sql) throws Exception {
		try (var statement = connection.createStatement(); var resultSet = statement.executeQuery(sql)) {
			assertThat(resultSet.next()).isTrue();
			return resultSet.getLong(1);
		}
	}

	private static DataSource closeFailingDataSource() {
		ResultSet resultSet = proxy(ResultSet.class, (proxy, method, args) -> switch (method.getName()) {
			case "next" -> true;
			case "getInt" -> 1;
			case "close" -> null;
			default -> throw new UnsupportedOperationException(method.getName());
		});
		PreparedStatement statement = proxy(PreparedStatement.class,
				(proxy, method, args) -> switch (method.getName()) {
					case "setString", "close" -> null;
					case "executeQuery" -> resultSet;
					default -> throw new UnsupportedOperationException(method.getName());
				});
		Connection connection = proxy(Connection.class, (proxy, method, args) -> switch (method.getName()) {
			case "prepareStatement" -> statement;
			case "close" -> throw new SQLException("secret close jdbc metadata");
			default -> throw new UnsupportedOperationException(method.getName());
		});
		return proxy(DataSource.class, (proxy, method, args) -> {
			if ("getConnection".equals(method.getName())) {
				return connection;
			}
			throw new UnsupportedOperationException(method.getName());
		});
	}

	private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler));
	}

}
