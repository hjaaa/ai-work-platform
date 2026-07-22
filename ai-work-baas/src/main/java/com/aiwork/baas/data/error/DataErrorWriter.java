package com.aiwork.baas.data.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * 用数据面独立 ObjectMapper 写错误响应,filter 与 controller advice 共用。
 *
 * @author ai-work
 * @date 2026/07/21
 */
public class DataErrorWriter {

    private final ObjectMapper objectMapper;

    public DataErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, DataApiException exception) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setStatus(exception.status());
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getOutputStream(),
                new DataErrorBody(exception.code(), exception.getMessage(), exception.details(), exception.hint()));
    }

}
