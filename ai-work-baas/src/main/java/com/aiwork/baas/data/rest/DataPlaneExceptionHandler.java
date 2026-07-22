package com.aiwork.baas.data.rest;

import com.aiwork.baas.data.error.DataApiException;
import com.aiwork.baas.data.error.DataErrorWriter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;

/**
 * 数据面异常出口(spec §7.5:限定 com.aiwork.baas.data 包,输出 §11 错误体,不复用平台 R)。
 *
 * @author ai-work
 * @date 2026/07/21
 */
@Slf4j
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.aiwork.baas.data")
public class DataPlaneExceptionHandler {

    private final DataErrorWriter errorWriter;

    @ExceptionHandler(DataApiException.class)
    public void handleDataApiException(DataApiException exception, HttpServletResponse response) throws IOException {
        errorWriter.write(response, exception);
    }

    @ExceptionHandler(Exception.class)
    public void handleUnexpected(Exception exception, HttpServletResponse response) throws IOException {
        // 保留堆栈日志(Plan B 评审教训:异常处理器不得静默吞堆栈),但错误体固定脱敏
        log.error("data plane unexpected exception", exception);
        errorWriter.write(response, DataApiException.internal("服务暂时不可用"));
    }

}
