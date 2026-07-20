/*
 *
 *      Copyright (c) 2018-2026, lengleng All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice,
 *  this list of conditions and the following disclaimer.
 *  Redistributions in binary form must reproduce the above copyright
 *  notice, this list of conditions and the following disclaimer in the
 *  documentation and/or other materials provided with the distribution.
 *  Neither the name of the pig4cloud.com developer nor the names of its
 *  contributors may be used to endorse or promote products derived from
 *  this software without specific prior written permission.
 *  Author: lengleng (wangiegie@gmail.com)
 *
 */

package com.aiwork.baas.ddl.lock;

import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/** GET_LOCK 与 DDL 共用物理连接，并确认 RELEASE_LOCK 或连接关闭至少一项成功。 */
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
        Connection connection;
        try {
            connection = provisionerDataSource.getConnection();
        }
        catch (Exception exception) {
            throw new DdlLockInfrastructureException(false);
        }
        try {
            int acquired = lockResult(connection, "SELECT GET_LOCK(?, 0)", name);
            if (acquired == 0) {
                throw new DdlLockBusyException("该项目有 DDL 操作进行中(advisory lock busy)");
            }
            if (acquired != 1) {
                throw new DdlLockInfrastructureException(false);
            }
        }
        catch (DdlLockBusyException | DdlLockInfrastructureException exception) {
            close(connection, name);
            throw exception;
        }
        catch (Exception exception) {
            close(connection, name);
            throw new DdlLockInfrastructureException(false);
        }

        String originalSqlMode;
        try {
            originalSqlMode = configureDeterministicSqlMode(connection);
        }
        catch (Exception exception) {
            release(connection, name);
            close(connection, name);
            throw new DdlLockInfrastructureException(false);
        }

        T result = null;
        RuntimeException callbackFailure = null;
        Error callbackError = null;
        try {
            result = callback.doWithConnection(connection);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            callbackFailure = new IllegalStateException("DDL_LOCK_CALLBACK_INTERRUPTED");
        }
        catch (RuntimeException exception) {
            callbackFailure = exception;
        }
        catch (Exception exception) {
            callbackFailure = new IllegalStateException("DDL_LOCK_CALLBACK_FAILED");
        }
        catch (Error error) {
            callbackError = error;
        }

        boolean modeRestored = restoreSqlMode(connection, originalSqlMode, name);
        boolean released = release(connection, name);
        boolean closed = close(connection, name);
        if ((!released || !modeRestored) && !closed) {
            throw new DdlLockInfrastructureException(true);
        }
        if (callbackError != null) {
            throw callbackError;
        }
        if (callbackFailure != null) {
            throw callbackFailure;
        }
        return result;
    }

    private String configureDeterministicSqlMode(Connection connection) throws Exception {
        String original;
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT @@SESSION.sql_mode")) {
            if (!resultSet.next()) {
                throw new IllegalStateException("missing sql mode");
            }
            original = resultSet.getString(1);
        }
        String deterministic = original == null ? ""
                : java.util.Arrays.stream(original.split(","))
                    .filter(mode -> !"NO_BACKSLASH_ESCAPES".equalsIgnoreCase(mode))
                    .collect(java.util.stream.Collectors.joining(","));
        try (PreparedStatement statement = connection.prepareStatement("SET SESSION sql_mode = ?")) {
            statement.setString(1, deterministic);
            statement.executeUpdate();
        }
        return original == null ? "" : original;
    }

    private boolean restoreSqlMode(Connection connection, String original, String name) {
        try (PreparedStatement statement = connection.prepareStatement("SET SESSION sql_mode = ?")) {
            statement.setString(1, original);
            statement.executeUpdate();
            return true;
        }
        catch (Exception exception) {
            log.warn("restore advisory sql mode failed lockName={} errorType={}", name,
                    exception.getClass().getSimpleName());
            return false;
        }
    }

    /** 1=成功，0=busy/not-owner，-1=NULL/空结果/非法值。 */
    private int lockResult(Connection connection, String sql, String name) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return -1;
                }
                int value = resultSet.getInt(1);
                return resultSet.wasNull() || (value != 0 && value != 1) ? -1 : value;
            }
        }
    }

    private boolean release(Connection connection, String name) {
        try {
            return lockResult(connection, "SELECT RELEASE_LOCK(?)", name) == 1;
        }
        catch (Exception exception) {
            log.warn("release advisory lock failed lockName={} errorType={}", name,
                    exception.getClass().getSimpleName());
            return false;
        }
    }

    private boolean close(Connection connection, String name) {
        try {
            connection.close();
            return true;
        }
        catch (Exception exception) {
            log.warn("close advisory connection failed lockName={} errorType={}", name,
                    exception.getClass().getSimpleName());
            return false;
        }
    }

}
