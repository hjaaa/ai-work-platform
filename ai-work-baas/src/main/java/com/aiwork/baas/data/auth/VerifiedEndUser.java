package com.aiwork.baas.data.auth;

/**
 * 验签通过的终端用户身份。
 *
 * @author ai-work
 * @date 2026/07/21
 */
public record VerifiedEndUser(Long userId, String sessionId) {
}
