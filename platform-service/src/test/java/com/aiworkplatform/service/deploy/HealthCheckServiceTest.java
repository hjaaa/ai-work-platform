package com.aiworkplatform.service.deploy;

import com.aiworkplatform.service.chat.MessagePushService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HealthCheckServiceTest {

    private DeployConfig deployConfig;
    private MessagePushService messagePushService;
    private HealthCheckService healthCheckService;

    @BeforeEach
    void setUp() {
        deployConfig = new DeployConfig();
        deployConfig.setHealthCheckTimeoutSeconds(5);
        deployConfig.setHealthCheckIntervalSeconds(1);
        messagePushService = mock(MessagePushService.class);
        healthCheckService = new HealthCheckService(deployConfig, messagePushService);
    }

    @Test
    void should_returnFalse_when_urlUnreachable() {
        boolean result = healthCheckService.checkHealth("http://127.0.0.1:19999/actuator/health");
        assertFalse(result);
    }

    @Test
    void should_returnFalse_when_waitTimeoutOnUnreachableHost() {
        // 超时设置为 2 秒，间隔 1 秒
        deployConfig.setHealthCheckTimeoutSeconds(2);
        deployConfig.setHealthCheckIntervalSeconds(1);

        boolean result = healthCheckService.waitForHealthy("http://127.0.0.1:19999", "test-proj");

        assertFalse(result);
    }
}
