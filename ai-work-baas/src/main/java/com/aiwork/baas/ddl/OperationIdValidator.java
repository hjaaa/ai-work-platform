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

package com.aiwork.baas.ddl;

import com.aiwork.baas.exception.BaasBadRequestException;

import java.util.UUID;

/** HTTP 表管理操作 ID 的规范 UUID 校验。 */
public final class OperationIdValidator {

    private OperationIdValidator() {
    }

    public static void requireUuid(String operationId) {
        if (operationId == null || operationId.isBlank()) {
            throw new BaasBadRequestException("operationId 必须是客户端生成的 UUID");
        }
        try {
            UUID parsed = UUID.fromString(operationId);
            if (!parsed.toString().equals(operationId)) {
                throw new BaasBadRequestException("operationId 必须使用规范小写 UUID 格式");
            }
        }
        catch (IllegalArgumentException exception) {
            throw new BaasBadRequestException("operationId 必须是客户端生成的 UUID");
        }
    }

}
