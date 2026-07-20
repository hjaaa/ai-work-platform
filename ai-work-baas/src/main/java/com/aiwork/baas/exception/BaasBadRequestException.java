/*
 *
 *      Copyright (c) 2018-2026, lengleng All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 */

package com.aiwork.baas.exception;

/**
 * 表管理请求校验失败(spec §7.3/§13),映射 400,message 面向调用方。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public class BaasBadRequestException extends RuntimeException {

    public BaasBadRequestException(String message) {
        super(message);
    }

}
