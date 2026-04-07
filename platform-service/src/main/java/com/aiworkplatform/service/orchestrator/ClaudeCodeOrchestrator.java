package com.aiworkplatform.service.orchestrator;

import com.aiworkplatform.common.exception.BusinessException;
import com.aiworkplatform.common.util.ProcessExecutor;
import com.aiworkplatform.service.chat.MessagePushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.concurrent.Semaphore;

/**
 * Claude Code CLI 编排器
 * 核心职责：并发限制 + 流式输出推送，进程执行委托给 ProcessExecutor
 */
@Service
public class ClaudeCodeOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeOrchestrator.class);

    private final OrchestratorConfig config;
    private final MessagePushService messagePushService;
    private final Semaphore concurrencyLimiter;

    public ClaudeCodeOrchestrator(OrchestratorConfig config,
                                  MessagePushService messagePushService) {
        this.config = config;
        this.messagePushService = messagePushService;
        this.concurrencyLimiter = new Semaphore(config.getMaxConcurrent());
    }

    public String execute(String projectId, Path workDir, String prompt) {
        if (!concurrencyLimiter.tryAcquire()) {
            throw new BusinessException(429, "当前生成任务已满，请稍后再试（最大并发: " + config.getMaxConcurrent() + "）");
        }

        try {
            log.info("开始 Claude Code 生成: projectId={}, workDir={}", projectId, workDir);
            messagePushService.pushProgress(projectId, "正在启动 AI 代码生成...");

            String result = ProcessExecutor.execute(
                    workDir, config.getTimeoutMinutes(),
                    line -> messagePushService.pushAssistantMessage(projectId, line),
                    config.getClaudeCliPath(), "-p", prompt, "--output-format", "text"
            );

            log.info("Claude Code 生成完成: projectId={}, outputLength={}", projectId, result.length());
            return result;
        } finally {
            concurrencyLimiter.release();
        }
    }

    public int availablePermits() {
        return concurrencyLimiter.availablePermits();
    }
}
