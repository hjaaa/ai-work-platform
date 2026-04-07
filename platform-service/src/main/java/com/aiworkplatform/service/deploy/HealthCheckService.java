package com.aiworkplatform.service.deploy;

import com.aiworkplatform.service.chat.MessagePushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.HttpURLConnection;
import java.net.URI;

/**
 * 健康检查服务
 * 部署完成后轮询检查应用是否正常启动
 */
@Service
public class HealthCheckService {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckService.class);

    private final DeployConfig deployConfig;
    private final MessagePushService messagePushService;

    public HealthCheckService(DeployConfig deployConfig,
                              MessagePushService messagePushService) {
        this.deployConfig = deployConfig;
        this.messagePushService = messagePushService;
    }

    /**
     * 等待应用健康检查通过
     *
     * @param baseUrl   应用基础地址（如 http://10.0.1.5:8080）
     * @param projectId 项目标识（用于推送进度）
     * @return true=健康检查通过, false=超时未通过
     */
    public boolean waitForHealthy(String baseUrl, String projectId) {
        String healthUrl = baseUrl + "/actuator/health";
        int timeoutSeconds = deployConfig.getHealthCheckTimeoutSeconds();
        int intervalSeconds = deployConfig.getHealthCheckIntervalSeconds();
        int maxAttempts = timeoutSeconds / intervalSeconds;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (checkHealth(healthUrl)) {
                log.info("健康检查通过: url={}, attempt={}", healthUrl, attempt);
                return true;
            }

            log.debug("健康检查未通过，等待重试: url={}, attempt={}/{}", healthUrl, attempt, maxAttempts);
            if (attempt % 3 == 0) {
                messagePushService.pushProgress(projectId,
                        "健康检查中... (" + attempt * intervalSeconds + "s/" + timeoutSeconds + "s)");
            }

            try {
                Thread.sleep(intervalSeconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("健康检查被中断: projectId={}", projectId);
                return false;
            }
        }

        log.warn("健康检查超时: url={}, timeout={}s", healthUrl, timeoutSeconds);
        return false;
    }

    /**
     * 单次健康检查
     */
    boolean checkHealth(String url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);

            int responseCode = connection.getResponseCode();
            connection.disconnect();
            return responseCode == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
