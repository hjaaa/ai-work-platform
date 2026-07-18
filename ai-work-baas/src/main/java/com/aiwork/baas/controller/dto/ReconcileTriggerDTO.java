/*
 *
 *      Copyright (c) 2018-2025, lengleng All rights reserved.
 *
 */

package com.aiwork.baas.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 手动对账请求(spec §7.3:operationId 放在 body)。
 *
 * @author ai-work
 * @date 2026/07/19
 */
public record ReconcileTriggerDTO(@NotBlank @Size(max = 64) String operationId) {
}
