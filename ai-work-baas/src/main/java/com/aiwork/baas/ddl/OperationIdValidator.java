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
