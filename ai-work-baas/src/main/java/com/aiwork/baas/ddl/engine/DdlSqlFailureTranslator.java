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

package com.aiwork.baas.ddl.engine;

import com.aiwork.baas.exception.DdlConflictException;
import com.aiwork.baas.exception.DdlExecutionException;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;

import java.sql.SQLException;
import java.util.Set;

/**
 * 把 Spring JDBC 异常翻译为固定信息的 409/500,绝不复制原始异常 message。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public final class DdlSqlFailureTranslator {

    private static final Set<Integer> DATA_CONFLICT_CODES = Set.of(1062, 1138, 1264, 1265, 1292, 1366, 1406);

    private DdlSqlFailureTranslator() {
    }

    public static RuntimeException translate(DataAccessException failure) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(failure);
        SQLException sql = root instanceof SQLException sqlException ? sqlException : null;
        String sqlState = sql == null ? null : safeSqlState(sql.getSQLState());
        int vendorCode = sql == null ? 0 : sql.getErrorCode();
        if ((sqlState != null && (sqlState.startsWith("22") || sqlState.startsWith("23")))
                || DATA_CONFLICT_CODES.contains(vendorCode)) {
            return new DdlConflictException("DDL 与现有数据不兼容");
        }
        return new DdlExecutionException("DDL_EXECUTION_FAILED", sqlState, vendorCode);
    }

    private static String safeSqlState(String sqlState) {
        return sqlState != null && sqlState.matches("[A-Z0-9]{5}") ? sqlState : "UNKNOWN";
    }

}
