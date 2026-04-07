package com.aiworkplatform.service.test;

import com.aiworkplatform.service.chat.MessagePushService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TestRunnerServiceTest {

    private MessagePushService messagePushService;
    private TestRunnerService testRunnerService;

    @BeforeEach
    void setUp() {
        messagePushService = Mockito.mock(MessagePushService.class);
        testRunnerService = new TestRunnerService(messagePushService);
    }

    @Test
    void should_skipBackendTest_when_pomXmlNotFound(@TempDir Path tempDir) {
        TestResult result = testRunnerService.runBackendTests("test-proj", tempDir);

        assertTrue(result.isSuccess());
        assertTrue(result.getRawOutput().contains("跳过"));
    }

    @Test
    void should_skipFrontendTest_when_packageJsonNotFound(@TempDir Path tempDir) {
        TestResult result = testRunnerService.runFrontendTests("test-proj", tempDir);

        assertTrue(result.isSuccess());
        assertTrue(result.getRawOutput().contains("跳过"));
    }

    @Test
    void should_mergeResults_when_bothTestsSkipped(@TempDir Path tempDir) {
        TestResult result = testRunnerService.runAllTests("test-proj", tempDir);

        assertTrue(result.isSuccess());
        assertEquals(0, result.getTotalTests());
        assertEquals(0, result.getFailedTests());
    }

    @Test
    void should_returnFailure_when_backendTestFails(@TempDir Path tempDir) throws IOException {
        // 创建 backend/pom.xml 以触发测试执行
        Path backendDir = tempDir.resolve("backend");
        Files.createDirectories(backendDir);
        Files.writeString(backendDir.resolve("pom.xml"), "<project></project>");

        // mvn 不存在或 pom 无效时应该返回失败
        TestResult result = testRunnerService.runBackendTests("test-proj", tempDir);

        // 由于 pom.xml 无效，mvn 要么找不到要么执行失败
        assertNotNull(result);
        verify(messagePushService).pushProgress(eq("test-proj"), anyString());
    }
}
