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

import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 第二层 DDL 锁(spec §9.2)：GET_LOCK 与后续 DDL 使用同一条 Provisioner 物理连接， 持有到回调结束才
 * RELEASE_LOCK，连接终止为崩溃兜底。
 *
 * @author ai-work
 * @date 2026/07/18
 */
@Slf4j
public class AdvisoryLockTemplate {

	private final DataSource provisionerDataSource;

	public AdvisoryLockTemplate(DataSource provisionerDataSource) {
		this.provisionerDataSource = provisionerDataSource;
	}

	public static String lockName(Long projectId) {
		return "baas_ddl_" + projectId;
	}

	public <T> T executeWithLock(Long projectId, DdlConnectionCallback<T> callback) {
		String name = lockName(projectId);
		Connection connection = null;
		try {
			connection = provisionerDataSource.getConnection();
			if (!acquire(connection, name)) {
				throw new DdlLockBusyException("该项目有 DDL 操作进行中(advisory lock busy)");
			}
			try {
				return callback.doWithConnection(connection);
			}
			catch (InterruptedException interruptedException) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("DDL_LOCK_CALLBACK_INTERRUPTED");
			}
			catch (RuntimeException runtimeException) {
				throw runtimeException;
			}
			catch (Exception exception) {
				throw new IllegalStateException("DDL_LOCK_CALLBACK_FAILED");
			}
			finally {
				releaseQuietly(connection, name);
			}
		}
		catch (SQLException sqlException) {
			throw new IllegalStateException("DDL_LOCK_INFRASTRUCTURE_FAILED");
		}
		finally {
			closeQuietly(connection, name);
		}
	}

	private boolean acquire(Connection connection, String name) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
			statement.setString(1, name);
			try (ResultSet resultSet = statement.executeQuery()) {
				return resultSet.next() && resultSet.getInt(1) == 1;
			}
		}
	}

	private void releaseQuietly(Connection connection, String name) {
		try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
			statement.setString(1, name);
			statement.executeQuery();
		}
		catch (Exception exception) {
			log.warn("release advisory lock failed lockName={} errorType={}", name,
					exception.getClass().getSimpleName());
		}
	}

	private void closeQuietly(Connection connection, String name) {
		if (connection == null) {
			return;
		}
		try {
			connection.close();
		}
		catch (Exception exception) {
			log.warn("close advisory connection failed lockName={} errorType={}", name,
					exception.getClass().getSimpleName());
		}
	}

}
