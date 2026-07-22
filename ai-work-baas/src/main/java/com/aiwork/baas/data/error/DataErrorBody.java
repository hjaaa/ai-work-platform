package com.aiwork.baas.data.error;

/**
 * PostgREST 风格错误体(spec §11)。
 *
 * @author ai-work
 * @date 2026/07/21
 */
public record DataErrorBody(String code, String message, String details, String hint) {
}
