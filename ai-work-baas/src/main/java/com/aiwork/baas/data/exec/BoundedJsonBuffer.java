package com.aiwork.baas.data.exec;

import com.aiwork.baas.data.error.DataApiException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import jakarta.servlet.http.HttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 有界响应缓冲(spec §13):逐行 flush 计数,超上限在响应提交前抛 413;
 * 缓冲总量由并发响应信号量约束(许可数 × 上限)。
 *
 * @author ai-work
 * @date 2026/07/21
 */
public class BoundedJsonBuffer implements AutoCloseable {

    private final LimitedByteArrayOutputStream buffer;

    private final JsonGenerator generator;

    /**
     * 创建有硬字节上限的 JSON 缓冲区。
     * @param factory JSON 生成器工厂
     * @param maxBytes 最大缓冲字节数
     * @throws IOException 创建 JSON 生成器失败
     */
    public BoundedJsonBuffer(JsonFactory factory, long maxBytes) throws IOException {
        this.buffer = new LimitedByteArrayOutputStream(maxBytes);
        this.generator = factory.createGenerator(buffer);
    }

    /**
     * 获取写入当前缓冲区的 JSON 生成器。
     * @return JSON 生成器
     */
    public JsonGenerator generator() {
        return generator;
    }

    /**
     * 刷新生成器并检查字节上限；超限时抛出 413，由调用方回滚事务。
     * @throws IOException 刷新失败
     */
    public void checkLimit() throws IOException {
        generator.flush();
    }

    /**
     * 关闭生成器并把完整缓冲写入尚未提交的 HTTP 响应。
     * @param response HTTP 响应
     * @param status HTTP 状态码
     * @throws IOException 响应写入失败
     */
    public void writeTo(HttpServletResponse response, int status) throws IOException {
        generator.close();
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setContentLength(buffer.size());
        buffer.writeTo(response.getOutputStream());
    }

    /**
     * 关闭 JSON 生成器。
     * @throws IOException 关闭失败
     */
    @Override
    public void close() throws IOException {
        generator.close();
    }

    int bufferedBytes() {
        return buffer.size();
    }

    /** 在 ByteArrayOutputStream 扩容前拒绝,不允许单个超大行暂时突破堆上限。 */
    private static final class LimitedByteArrayOutputStream extends ByteArrayOutputStream {

        private final long maxBytes;

        private LimitedByteArrayOutputStream(long maxBytes) {
            super((int)Math.min(8192L, maxBytes));
            this.maxBytes = maxBytes;
        }

        @Override
        public synchronized void write(int value) {
            ensureWithinLimit(1);
            super.write(value);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            ensureWithinLimit(length);
            super.write(bytes, offset, length);
        }

        private void ensureWithinLimit(int additionalBytes) {
            if ((long)count + additionalBytes > maxBytes) {
                throw DataApiException.payloadTooLarge("响应体超过 " + maxBytes + " 字节上限");
            }
        }

    }

}
