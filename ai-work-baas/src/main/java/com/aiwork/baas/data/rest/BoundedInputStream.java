package com.aiwork.baas.data.rest;

import com.aiwork.baas.data.error.DataApiException;

import java.io.IOException;
import java.io.InputStream;

/**
 * 请求体流式计数兜底(spec §13):逐字节累计,超上限抛 413。
 * Content-Length 预检之外的第二道防线——缺失/chunked Content-Length 时仍能阻断内存放大。
 *
 * @author ai-work
 * @date 2026/07/22
 */
public final class BoundedInputStream extends InputStream {

    private final InputStream delegate;

    private final long maxBytes;

    private long readBytes;

    public BoundedInputStream(InputStream delegate, long maxBytes) {
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
