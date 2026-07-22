package com.aiwork.baas.data.error;

/**
 * 数据面统一异常(spec §11 错误体 + HTTP 语义)。
 *
 * @author ai-work
 * @date 2026/07/21
 */
public class DataApiException extends RuntimeException {

    private final int status;

    private final String code;

    private final String details;

    private final String hint;

    public DataApiException(int status, String code, String message, String details, String hint) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
        this.hint = hint;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String details() {
        return details;
    }

    public String hint() {
        return hint;
    }

    public static DataApiException badRequest(String message) {
        return new DataApiException(400, "BAD_REQUEST", message, null, null);
    }

    public static DataApiException badRequest(String message, String hint) {
        return new DataApiException(400, "BAD_REQUEST", message, null, hint);
    }

    public static DataApiException unauthorized(String message) {
        return new DataApiException(401, "UNAUTHORIZED", message, null, null);
    }

    public static DataApiException forbidden(String message, String hint) {
        return new DataApiException(403, "FORBIDDEN", message, null, hint);
    }

    public static DataApiException notFound(String message) {
        return new DataApiException(404, "NOT_FOUND", message, null, null);
    }

    public static DataApiException conflict(String message) {
        return new DataApiException(409, "CONFLICT", message, null, null);
    }

    public static DataApiException payloadTooLarge(String message) {
        return new DataApiException(413, "PAYLOAD_TOO_LARGE", message, null, null);
    }

    public static DataApiException tooManyRequests(String message) {
        return new DataApiException(429, "RATE_LIMITED", message, null, null);
    }

    public static DataApiException internal(String message) {
        return new DataApiException(500, "INTERNAL", message, null, null);
    }

}
