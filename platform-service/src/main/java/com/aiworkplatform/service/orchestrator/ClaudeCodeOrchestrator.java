package com.aiworkplatform.service.orchestrator;

import com.aiworkplatform.common.exception.BusinessException;
import com.aiworkplatform.common.util.ProcessExecutor;
import com.aiworkplatform.service.chat.MessagePushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Claude Code CLI 编排器
 * 核心职责：会话管理（session-id/resume）、并发限制、流式输出推送、取消支持
 */
@Service
public class ClaudeCodeOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeOrchestrator.class);

    private final OrchestratorConfig config;
    private final MessagePushService messagePushService;
    private final Semaphore concurrencyLimiter;

    /** 按 threadId 跟踪正在运行的进程，用于 cancel */
    private final ConcurrentHashMap<String, Process> runningProcesses = new ConcurrentHashMap<>();

    public ClaudeCodeOrchestrator(OrchestratorConfig config,
                                  MessagePushService messagePushService) {
        this.config = config;
        this.messagePushService = messagePushService;
        this.concurrencyLimiter = new Semaphore(config.getMaxConcurrent());
    }

    /**
     * 使用 session 模式执行 Claude CLI
     *
     * @param threadId  线程 ID，用于进程跟踪和取消
     * @param workDir   工作目录
     * @param prompt    用户当前消息（不需要拼历史，CLI 通过 session 自动管理）
     * @param sessionId Claude CLI session ID，null 表示首次对话
     * @return 执行结果，包含 AI 回复和 session ID
     */
    public ExecutionResult execute(String threadId, Path workDir, String prompt, String sessionId) {
        if (!concurrencyLimiter.tryAcquire()) {
            throw new BusinessException(429, "当前生成任务已满，请稍后再试（最大并发: " + config.getMaxConcurrent() + "）");
        }

        AtomicReference<Process> processRef = new AtomicReference<>();
        try {
            log.info("开始 Claude Code 生成: threadId={}, hasSession={}", threadId, sessionId != null);
            messagePushService.pushProgressToThread(threadId, null, "正在思考...");

            // 构建命令参数
            List<String> cmdList = buildCommand(prompt, sessionId);
            String[] cmd = cmdList.toArray(new String[0]);

            // 用于从 stream-json 中累积结果
            StringBuilder fullOutput = new StringBuilder();
            AtomicReference<String> resultSessionId = new AtomicReference<>(sessionId);

            StreamJsonParser parser = new StreamJsonParser(
                    text -> messagePushService.pushAssistantMessageToThread(threadId, null, text));

            String rawOutput = ProcessExecutor.execute(
                    workDir, config.getTimeoutMinutes(),
                    line -> {
                        // 首次收到输出时注册进程到 map（processRef 此时已被 ProcessExecutor 设置）
                        Process p = processRef.get();
                        if (p != null) {
                            runningProcesses.putIfAbsent(threadId, p);
                        }
                        parser.parseLine(line, fullOutput, resultSessionId);
                    },
                    processRef,
                    cmd
            );

            // 如果被 cancel 了，进程被 destroyForcibly，会抛异常走 catch
            // 正常完成时，从 stream-json 解析结果
            String output = fullOutput.length() > 0 ? fullOutput.toString() : rawOutput;
            String resolvedSessionId = resultSessionId.get();

            log.info("Claude Code 生成完成: threadId={}, sessionId={}, outputLength={}",
                    threadId, resolvedSessionId, output.length());

            return new ExecutionResult(output, resolvedSessionId);

        } catch (BusinessException e) {
            // 检查是否是用户主动取消
            if (!runningProcesses.containsKey(threadId) && processRef.get() != null && !processRef.get().isAlive()) {
                log.info("用户取消生成: threadId={}", threadId);
                return ExecutionResult.cancelled();
            }
            throw e;
        } finally {
            runningProcesses.remove(threadId);
            concurrencyLimiter.release();
        }
    }

    /**
     * 取消指定线程的正在运行的生成任务
     */
    public boolean cancel(String threadId) {
        Process process = runningProcesses.remove(threadId);
        if (process != null && process.isAlive()) {
            log.info("取消 Claude Code 生成: threadId={}", threadId);
            process.destroyForcibly();
            return true;
        }
        return false;
    }

    /**
     * 检查指定线程是否有正在运行的任务
     */
    public boolean isRunning(String threadId) {
        Process process = runningProcesses.get(threadId);
        return process != null && process.isAlive();
    }

    public int availablePermits() {
        return concurrencyLimiter.availablePermits();
    }

    /**
     * 构建 Claude CLI 命令
     */
    private List<String> buildCommand(String prompt, String sessionId) {
        List<String> cmd = new ArrayList<>();
        cmd.add(config.getClaudeCliPath());
        cmd.add("-p");
        cmd.add(prompt);
        cmd.add("--output-format");
        cmd.add("stream-json");
        cmd.add("--verbose");

        if (config.isSkipPermissions()) {
            cmd.add("--dangerously-skip-permissions");
        }

        if (sessionId != null) {
            // 后续消息：恢复已有会话
            cmd.add("--resume");
            cmd.add(sessionId);
        } else {
            // 首次消息：创建新会话
            String newSessionId = UUID.randomUUID().toString();
            cmd.add("--session-id");
            cmd.add(newSessionId);
        }

        return cmd;
    }

    /**
     * 旧接口，兼容非 session 模式调用
     */
    @Deprecated
    public String execute(String projectId, Path workDir, String prompt) {
        ExecutionResult result = execute(projectId, workDir, prompt, null);
        return result.getOutput();
    }
}
