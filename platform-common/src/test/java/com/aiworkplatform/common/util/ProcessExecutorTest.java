package com.aiworkplatform.common.util;

import com.aiworkplatform.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ProcessExecutorTest {

    @TempDir
    Path tempDir;

    // --- processRef 被正确设置 ---

    @Test
    void should_setProcessRef_when_processStarts() {
        AtomicReference<Process> processRef = new AtomicReference<>();

        ProcessExecutor.execute(tempDir, 1, null, processRef, "echo", "hello");

        // 进程执行完后，processRef 应该被设置过（非 null）
        assertNotNull(processRef.get());
    }

    @Test
    void should_setProcessRef_when_usingStreamMode() {
        AtomicReference<Process> processRef = new AtomicReference<>();
        List<String> lines = new ArrayList<>();

        ProcessExecutor.execute(tempDir, 1, lines::add, processRef, "echo", "hello");

        assertNotNull(processRef.get());
        assertFalse(lines.isEmpty());
    }

    @Test
    void should_workNormally_when_processRefIsNull() {
        // processRef 为 null 时不应报错（兼容旧调用方式）
        AtomicReference<Process> nullRef = null;
        String output = ProcessExecutor.execute(tempDir, 1, null, nullRef, "echo", "test");

        assertTrue(output.contains("test"));
    }

    // --- 基础功能 ---

    @Test
    void should_returnOutput_when_commandSucceeds() {
        String output = ProcessExecutor.execute(tempDir, 1, "echo", "hello world");

        assertTrue(output.contains("hello world"));
    }

    @Test
    void should_callLineConsumer_when_streamMode() {
        List<String> lines = new ArrayList<>();

        ProcessExecutor.execute(tempDir, 1, lines::add, "echo", "line1");

        assertEquals(1, lines.size());
        assertEquals("line1", lines.get(0));
    }

    @Test
    void should_throwBusinessException_when_commandNotFound() {
        assertThrows(BusinessException.class, () ->
                ProcessExecutor.execute(tempDir, 1, "/nonexistent/command"));
    }

    @Test
    void should_throwBusinessException_when_commandFails() {
        assertThrows(BusinessException.class, () ->
                ProcessExecutor.execute(tempDir, 1, "false"));
    }

    // --- 取消场景 ---

    @Test
    void should_allowExternalCancel_when_processRefIsSet() throws Exception {
        AtomicReference<Process> processRef = new AtomicReference<>();

        // 启动一个会运行较长时间的进程，然后在另一个线程取消
        Thread executor = new Thread(() -> {
            try {
                ProcessExecutor.execute(tempDir, 1, null, processRef, "sleep", "10");
            } catch (BusinessException e) {
                // 预期：被取消后可能抛异常
            }
        });
        executor.start();

        // 等待 processRef 被设置
        long start = System.currentTimeMillis();
        while (processRef.get() == null && System.currentTimeMillis() - start < 5000) {
            Thread.sleep(50);
        }

        assertNotNull(processRef.get(), "processRef 应在进程启动后被设置");
        assertTrue(processRef.get().isAlive(), "进程应该还在运行");

        // 外部取消
        processRef.get().destroyForcibly();

        // 等待线程结束
        executor.join(5000);
        assertFalse(executor.isAlive(), "执行线程应已结束");
    }
}
