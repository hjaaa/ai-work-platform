package com.aiworkplatform.service.deploy;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 部署配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.deploy")
public class DeployConfig {

    private String dockerRegistry = "localhost:5000";
    private int healthCheckTimeoutSeconds = 60;
    private int healthCheckIntervalSeconds = 5;
    private List<ServerConfig> servers = new ArrayList<>();

    @Data
    public static class ServerConfig {
        private String name;
        private String host;
        private int port = 22;
        private String username = "root";
        private String privateKeyPath;
        private String deployPath = "/opt/deploy";
    }
}
