package com.aiworkplatform.service.orchestrator;

import com.aiworkplatform.common.exception.BusinessException;
import com.aiworkplatform.service.chat.MessagePushService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

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
                orch.execute("thread-1", Path.of("/tmp"), "test prompt", null));

        // 验证推送了进度消息
        verify(messagePushService).pushProgressToThread(eq("thread-1"), isNull(), anyString());
    }

    @Test
    void should_throwBusinessException_when_concurrencyExhausted() {
        config.setMaxConcurrent(0);
        ClaudeCodeOrchestrator orch = new ClaudeCodeOrchestrator(config, messagePushService);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                orch.execute("thread-1", Path.of("/tmp"), "test prompt", null));

        assertEquals(429, ex.getCode());
        assertTrue(ex.getMessage().contains("任务已满"));
    }

    @Test
    void should_returnFalse_when_cancelNonexistentThread() {
        assertFalse(orchestrator.cancel("nonexistent-thread"));
    }

    @Test
    void should_reportNotRunning_when_noTaskForThread() {
        assertFalse(orchestrator.isRunning("thread-1"));
    }

    // ========== skipPermissions 功能测试 ==========

    @SuppressWarnings("unchecked")
    private List<String> invokeBuildCommand(ClaudeCodeOrchestrator orch, String prompt, String sessionId) throws Exception {
        Method method = ClaudeCodeOrchestrator.class.getDeclaredMethod("buildCommand", String.class, String.class);
        method.setAccessible(true);
        return (List<String>) method.invoke(orch, prompt, sessionId);
    }

    @Test
    void should_includeDangerouslySkipPermissions_when_skipPermissionsEnabled() throws Exception {
        config.setSkipPermissions(true);
        ClaudeCodeOrchestrator orch = new ClaudeCodeOrchestrator(config, messagePushService);

        List<String> cmd = invokeBuildCommand(orch, "hello", null);

        assertTrue(cmd.contains("--dangerously-skip-permissions"),
                "开启 skipPermissions 时命令应包含 --dangerously-skip-permissions");
    }

    @Test
    void should_notIncludeDangerouslySkipPermissions_when_skipPermissionsDisabled() throws Exception {
        config.setSkipPermissions(false);
        ClaudeCodeOrchestrator orch = new ClaudeCodeOrchestrator(config, messagePushService);

        List<String> cmd = invokeBuildCommand(orch, "hello", null);

        assertFalse(cmd.contains("--dangerously-skip-permissions"),
                "关闭 skipPermissions 时命令不应包含 --dangerously-skip-permissions");
    }

    @Test
    void should_defaultSkipPermissionsToTrue() {
        OrchestratorConfig defaultConfig = new OrchestratorConfig();
        assertTrue(defaultConfig.isSkipPermissions(),
                "skipPermissions 默认值应为 true（程序化调用默认跳过审批）");
    }
}
