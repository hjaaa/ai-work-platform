package com.aiworkplatform.common.util;

import com.aiworkplatform.common.exception.BusinessException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 统一的外部进程执行工具
 * 支持静默模式（返回完整输出）和流式模式（逐行回调）
 */
public final class ProcessExecutor {

    private ProcessExecutor() {
    }

    /**
     * 静默执行，返回完整输出
     */
    public static String execute(Path workDir, int timeoutMinutes, String... command) {
        return execute(workDir, timeoutMinutes, null, command);
    }

    /**
     * 流式执行，每读到一行调用 lineConsumer
     */
    public static String execute(Path workDir, int timeoutMinutes,
                                 Consumer<String> lineConsumer, String... command) {
        return execute(workDir, timeoutMinutes, lineConsumer, null, command);
    }

    /**
     * 流式执行，支持通过 processRef 暴露进程句柄供外部取消
     *
     * @param processRef 非空时，进程启动后会 set 进去，外部可通过 processRef.get().destroyForcibly() 取消
     */
    public static String execute(Path workDir, int timeoutMinutes,
                                 Consumer<String> lineConsumer,
                                 AtomicReference<Process> processRef,
                                 String... command) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        // 将子进程 stdin 重定向为空，避免 Claude CLI 等待 stdin 超时产生 warning
        pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));

        Process process = null;
        try {
            process = pb.start();
            if (processRef != null) {
                processRef.set(process);
            }
            StringBuilder output = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    if (lineConsumer != null) {
                        lineConsumer.accept(line);
                    }
                }
            }

            boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new BusinessException("命令执行超时（超过 " + timeoutMinutes + " 分钟）");
            }

            if (process.exitValue() != 0) {
                String errorOutput = output.length() > 500
                        ? output.substring(0, 500) : output.toString();
                throw new BusinessException("命令执行失败（退出码 " + process.exitValue() + "）: " + errorOutput);
            }

            return output.toString();

        } catch (IOException e) {
            throw new BusinessException("命令启动失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            throw new BusinessException("命令执行被中断");
        }
    }
}
