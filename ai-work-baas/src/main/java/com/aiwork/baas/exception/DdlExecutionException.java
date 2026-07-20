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

package com.aiwork.baas.exception;

/**
 * 未知 DDL 执行失败。只携带可公开/持久化的结构化诊断,不保留原始 SQL 异常文本。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public final class DdlExecutionException extends RuntimeException {

    private final String errorCode;

    private final String sqlState;

    private final int vendorCode;

    public DdlExecutionException(String errorCode, String sqlState, int vendorCode) {
        super("DDL 执行失败");
        this.errorCode = errorCode;
        this.sqlState = sqlState;
        this.vendorCode = vendorCode;
    }

    public String errorCode() {
        return errorCode;
    }

    public String sqlState() {
        return sqlState;
    }

    public int vendorCode() {
        return vendorCode;
    }

}
