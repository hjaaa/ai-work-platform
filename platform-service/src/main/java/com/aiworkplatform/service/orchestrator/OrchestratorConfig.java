package com.aiworkplatform.service.orchestrator;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Claude Code CLI 编排器配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.orchestrator")
public class OrchestratorConfig {

    private String claudeCliPath = "/usr/local/bin/claude";
    private String workspaceBasePath = "/workspace/projects";
    private int maxConcurrent = 3;
    private int timeoutMinutes = 5;
}
