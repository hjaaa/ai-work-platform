package com.aiworkplatform.service.test;

import com.aiworkplatform.service.chat.MessagePushService;
import com.aiworkplatform.service.orchestrator.ClaudeCodeOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutoFixServiceTest {

    private TestRunnerService testRunnerService;
    private ClaudeCodeOrchestrator orchestrator;
    private MessagePushService messagePushService;
    private AutoFixService autoFixService;

    @BeforeEach
    void setUp() {
        testRunnerService = mock(TestRunnerService.class);
        orchestrator = mock(ClaudeCodeOrchestrator.class);
        messagePushService = mock(MessagePushService.class);
        autoFixService = new AutoFixService(testRunnerService, orchestrator, messagePushService);
    }

    @Test
    void should_returnSuccess_when_firstRoundPasses() {
        TestResult success = TestResult.builder()
                .success(true).totalTests(5).passedTests(5).failedTests(0).build();
        when(testRunnerService.runAllTests(anyString(), any(Path.class))).thenReturn(success);

        TestResult result = autoFixService.runWithAutoFix("proj-1", Path.of("/tmp/proj"));

        assertTrue(result.isSuccess());
        // 不应调用 orchestrator（无需修复）
        verify(orchestrator, never()).execute(anyString(), any(Path.class), anyString());
    }

    @Test
    void should_retryAndFix_when_firstRoundFails() {
        TestResult failure = TestResult.builder()
                .success(false).totalTests(5).passedTests(3).failedTests(2)
                .failureDetails(List.of("TestA failed", "TestB failed")).build();
        TestResult success = TestResult.builder()
                .success(true).totalTests(5).passedTests(5).failedTests(0).build();

        // 第一轮失败，第二轮成功
        when(testRunnerService.runAllTests(anyString(), any(Path.class)))
                .thenReturn(failure)
                .thenReturn(success);
        when(orchestrator.execute(anyString(), any(Path.class), anyString())).thenReturn("fixed");

        TestResult result = autoFixService.runWithAutoFix("proj-1", Path.of("/tmp/proj"));

        assertTrue(result.isSuccess());
        // orchestrator 被调用 1 次（修复第一轮的失败）
        verify(orchestrator, times(1)).execute(anyString(), any(Path.class), anyString());
    }

    @Test
    void should_stopAfterMaxRounds_when_allRoundsFail() {
        TestResult failure = TestResult.builder()
                .success(false).totalTests(5).passedTests(2).failedTests(3)
                .failureDetails(List.of("TestA failed")).build();

        // 每轮都失败
        when(testRunnerService.runAllTests(anyString(), any(Path.class))).thenReturn(failure);
        when(orchestrator.execute(anyString(), any(Path.class), anyString())).thenReturn("attempted fix");

        TestResult result = autoFixService.runWithAutoFix("proj-1", Path.of("/tmp/proj"));

        assertFalse(result.isSuccess());
        // 测试执行 3 次，修复调用 2 次（第三轮失败后不再修复）
        verify(testRunnerService, times(3)).runAllTests(anyString(), any(Path.class));
        verify(orchestrator, times(2)).execute(anyString(), any(Path.class), anyString());
        // 推送了"需要人工介入"的消息
        verify(messagePushService).pushProgress(eq("proj-1"), contains("开发人员介入"));
    }
}
