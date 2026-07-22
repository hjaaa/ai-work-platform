package com.aiwork.baas.data.rest;

import com.aiwork.baas.data.config.DataPlaneProperties;
import com.aiwork.baas.data.context.DataRequestContext;
import com.aiwork.baas.data.error.DataApiException;
import com.aiwork.baas.data.exec.DataPlaneExecutor;
import com.aiwork.baas.data.query.QueryParser;
import com.aiwork.common.security.annotation.Inner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;

/**
 * 数据面动态 CRUD 端点(spec §7.1);body 手动读写,不经平台 HttpMessageConverter(§7.5 序列化隔离)。
 *
 * @author ai-work
 * @date 2026/07/21
 */
@Inner(false)
@RestController
@RequestMapping("/data/{projectRef}/rest/v1/{table}")
public class DataRestController {

    private final DataPlaneExecutor executor;

    private final QueryParser queryParser;

    private final ObjectMapper objectMapper;

    private final DataPlaneProperties properties;

    public DataRestController(DataPlaneExecutor executor, QueryParser queryParser,
            @Qualifier("dataPlaneObjectMapper") ObjectMapper objectMapper, DataPlaneProperties properties) {
        this.executor = executor;
        this.queryParser = queryParser;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @GetMapping
    public void get(@PathVariable("table") String table, HttpServletRequest request, HttpServletResponse response) {
        executor.executeGet(context(request), table, queryParser.parse(request.getParameterMap()),
                PreferHeader.parse(request.getHeader("Prefer")), response);
    }

    @PostMapping
    public void post(@PathVariable("table") String table, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        executor.executePost(context(request), table, readBody(request),
                PreferHeader.parse(request.getHeader("Prefer")), response);
    }

    @PatchMapping
    public void patch(@PathVariable("table") String table, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        executor.executePatch(context(request), table, queryParser.parse(request.getParameterMap()),
                readBody(request), PreferHeader.parse(request.getHeader("Prefer")), response);
    }

    @DeleteMapping
    public void delete(@PathVariable("table") String table, HttpServletRequest request,
            HttpServletResponse response) {
        // DELETE 不支持 representation(spec §7.1)
        if (PreferHeader.parse(request.getHeader("Prefer")).returnRepresentation()) {
            throw DataApiException.badRequest("DELETE 不支持 return=representation");
        }
        executor.executeDelete(context(request), table, queryParser.parse(request.getParameterMap()), response);
    }

    private static DataRequestContext context(HttpServletRequest request) {
        DataRequestContext context = (DataRequestContext)request.getAttribute(DataRequestContext.ATTRIBUTE);
        if (context == null) {
            // 正常不可达:ApiKeyAuthFilter 保证 attribute 存在
            throw DataApiException.unauthorized("缺少鉴权上下文");
        }
        return context;
    }

    /** 流式兜底计数读取 body(spec §13:Content-Length 预检之外的第二道防线)。 */
    private JsonNode readBody(HttpServletRequest request) throws IOException {
        InputStream bounded = new BoundedInputStream(request.getInputStream(), properties.getBodyMaxBytes());
        try {
            JsonNode body = objectMapper.readTree(bounded);
            if (body == null || body.isMissingNode()) {
                throw DataApiException.badRequest("请求体不能为空");
            }
            return body;
        }
        catch (DataApiException exception) {
            throw exception;
        }
        catch (IOException exception) {
            throw DataApiException.badRequest("请求体不是合法 JSON");
        }
    }

    private static final class BoundedInputStream extends InputStream {

        private final InputStream delegate;

        private final long maxBytes;

        private long readBytes;

        private BoundedInputStream(InputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                count(read);
            }
            return read;
        }

        private void count(int read) {
            readBytes += read;
            if (readBytes > maxBytes) {
                throw DataApiException.payloadTooLarge("请求体超过 " + maxBytes + " 字节上限");
            }
        }

    }

}
