package com.aiwork.baas.data.error;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 数据面过滤器链最外层异常边界。MVC advice 无法处理过滤器异常，因此在响应尚未提交时
 * 统一输出脱敏数据面错误体；JVM {@link Error} 不在捕获范围内。
 *
 * @author ai-work
 * @date 2026/07/21
 */
@Slf4j
@RequiredArgsConstructor
public class DataPlaneErrorBoundaryFilter extends OncePerRequestFilter {

    private final DataErrorWriter errorWriter;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        }
        catch (Exception exception) {
            log.error("data plane filter chain unexpected exception", exception);
            if (response.isCommitted()) {
                rethrow(exception);
            }
            errorWriter.write(response, DataApiException.internal("服务暂时不可用"));
        }
    }

    private static void rethrow(Exception exception) throws ServletException, IOException {
        if (exception instanceof ServletException servletException) {
            throw servletException;
        }
        if (exception instanceof IOException ioException) {
            throw ioException;
        }
        throw new ServletException(exception);
    }

}
