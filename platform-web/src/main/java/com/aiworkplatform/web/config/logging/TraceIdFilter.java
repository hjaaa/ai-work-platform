package com.aiworkplatform.web.config.logging;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求入口自动注入 traceId
 * - 优先从请求头 X-Trace-Id 获取（支持链路透传）
 * - 没有则自动生成
 * - 放入 MDC，所有日志自动带上
 * - 响应头也返回 X-Trace-Id，方便前端排查
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter implements Filter {

    private static final String TRACE_ID_KEY = "traceId";
    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            String traceId = resolveTraceId((HttpServletRequest) request);
            MDC.put(TRACE_ID_KEY, traceId);

            if (response instanceof HttpServletResponse httpResponse) {
                httpResponse.setHeader(TRACE_ID_HEADER, traceId);
            }

            chain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID_KEY);
        }
    }

    private String resolveTraceId(HttpServletRequest request) {
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId != null && !traceId.isBlank()) {
            return traceId;
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
