package com.aiwork.baas.data.error;

/**
 * 限速拒绝(spec §12.2):429 + Retry-After(窗口剩余秒)。
 *
 * @author ai-work
 * @date 2026/07/22
 */
public class RateLimitedException extends DataApiException {

    private final long retryAfterSeconds;

    public RateLimitedException(String message, long retryAfterSeconds) {
        super(429, "RATE_LIMITED", message, null, null);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

}
