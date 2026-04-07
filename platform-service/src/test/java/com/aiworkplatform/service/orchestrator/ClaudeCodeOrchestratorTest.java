package com.aiworkplatform.service.orchestrator;

import com.aiworkplatform.common.exception.BusinessException;
import com.aiworkplatform.service.chat.MessagePushService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClaudeCodeOrchestratorTest {

    private OrchestratorConfig config;
    private MessagePushService messagePushService;
    private ClaudeCodeOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        config = new OrchestratorConfig();
        config.setMaxConcurrent(2);
        config.setTimeoutMinutes(1);
        config.setClaudeCliPath("/usr/local/bin/claude");
        messagePushService = Mockito.mock(MessagePushService.class);
        orchestrator = new ClaudeCodeOrchestrator(config, messagePushService);
    }

    @Test
    void should_reportAvailablePermits_when_noTaskRunning() {
        assertEquals(2, orchestrator.availablePermits());
    }

    @Test
    void should_throwBusinessException_when_cliPathInvalid() {
        config.setClaudeCliPath("/nonexistent/path/claude");
        ClaudeCodeOrchestrator orch = new ClaudeCodeOrchestrator(config, messagePushService);

        assertThrows(BusinessException.class, () ->
                orch.execute("test-proj", Path.of("/tmp"), "test prompt"));

        // 验证推送了进度消息
        verify(messagePushService).pushProgress(eq("test-proj"), anyString());
    }

    @Test
    void should_throwBusinessException_when_concurrencyExhausted() {
        config.setMaxConcurrent(0);
        ClaudeCodeOrchestrator orch = new ClaudeCodeOrchestrator(config, messagePushService);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                orch.execute("test-proj", Path.of("/tmp"), "test prompt"));

        assertEquals(429, ex.getCode());
        assertTrue(ex.getMessage().contains("任务已满"));
    }
}
