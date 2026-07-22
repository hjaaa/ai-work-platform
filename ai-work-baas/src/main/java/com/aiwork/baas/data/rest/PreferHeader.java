package com.aiwork.baas.data.rest;

/**
 * Prefer 头解析(spec §7.1:return=representation / count=exact;未知 token 按 HTTP 语义忽略)。
 *
 * @author ai-work
 * @date 2026/07/21
 */
public record PreferHeader(boolean returnRepresentation, boolean countExact) {

    /**
     * 解析 Prefer 请求头，识别 representation 与精确计数偏好。
     * @param headerValue Prefer 请求头值
     * @return 解析后的偏好；空值与空白值返回默认偏好
     */
    public static PreferHeader parse(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return new PreferHeader(false, false);
        }
        boolean representation = false;
        boolean countExact = false;
        for (String token : headerValue.split(",")) {
            String normalized = token.trim();
            if ("return=representation".equals(normalized)) {
                representation = true;
            } else if ("count=exact".equals(normalized)) {
                countExact = true;
            }
        }
        return new PreferHeader(representation, countExact);
    }

}
