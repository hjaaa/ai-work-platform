/*
 *
 *      Copyright (c) 2018-2025, lengleng All rights reserved.
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
 * DDL 并发/幂等冲突(spec §9.2):锁被占用、指纹不一致、状态不允许,映射 409。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public class DdlConflictException extends RuntimeException {

    public DdlConflictException(String message) {
        super(message);
    }

}
