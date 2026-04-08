package com.aiworkplatform.service.config;

import com.aiworkplatform.domain.entity.SystemConfig;
import com.aiworkplatform.domain.mapper.SystemConfigMapper;
import com.aiworkplatform.service.orchestrator.OrchestratorConfig;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 系统配置服务：从数据库加载配置，覆盖 yml 默认值
 */
@Service
public class SystemConfigService {

    private static final Logger log = LoggerFactory.getLogger(SystemConfigService.class);

    private static final String KEY_WORKSPACE_BASE_PATH = "workspace_base_path";
    private static final String KEY_CLAUDE_CLI_PATH = "claude_cli_path";

    private final SystemConfigMapper configMapper;
    private final OrchestratorConfig orchestratorConfig;

    public SystemConfigService(SystemConfigMapper configMapper, OrchestratorConfig orchestratorConfig) {
        this.configMapper = configMapper;
        this.orchestratorConfig = orchestratorConfig;
    }

    /**
     * 应用启动后从数据库加载配置，覆盖 yml 默认值
     */
    @EventListener(ApplicationReadyEvent.class)
    public void loadConfigFromDb() {
        try {
            Map<String, String> configs = getAllConfigs();
            if (configs.containsKey(KEY_WORKSPACE_BASE_PATH)) {
                orchestratorConfig.setWorkspaceBasePath(configs.get(KEY_WORKSPACE_BASE_PATH));
            }
            if (configs.containsKey(KEY_CLAUDE_CLI_PATH)) {
                orchestratorConfig.setClaudeCliPath(configs.get(KEY_CLAUDE_CLI_PATH));
            }
            log.info("系统配置已从数据库加载: workspaceBasePath={}, claudeCliPath={}",
                    orchestratorConfig.getWorkspaceBasePath(), orchestratorConfig.getClaudeCliPath());
        } catch (Exception e) {
            log.warn("从数据库加载配置失败，使用 yml 默认值: {}", e.getMessage());
        }
    }

    /**
     * 获取当前生效的配置
     */
    public Map<String, String> getEffectiveConfig() {
        return Map.of(
                KEY_WORKSPACE_BASE_PATH, orchestratorConfig.getWorkspaceBasePath(),
                KEY_CLAUDE_CLI_PATH, orchestratorConfig.getClaudeCliPath()
        );
    }

    /**
     * 保存配置到数据库，并同步更新内存
     */
    public void saveConfig(String workspaceBasePath, String claudeCliPath) {
        upsert(KEY_WORKSPACE_BASE_PATH, workspaceBasePath, "项目工作目录");
        upsert(KEY_CLAUDE_CLI_PATH, claudeCliPath, "Claude Code CLI 路径");

        orchestratorConfig.setWorkspaceBasePath(workspaceBasePath);
        orchestratorConfig.setClaudeCliPath(claudeCliPath);

        log.info("系统配置已保存: workspaceBasePath={}, claudeCliPath={}", workspaceBasePath, claudeCliPath);
    }

    private void upsert(String key, String value, String description) {
        SystemConfig existing = configMapper.selectOne(
                new LambdaQueryWrapper<SystemConfig>().eq(SystemConfig::getConfigKey, key));
        if (existing != null) {
            existing.setConfigValue(value);
            configMapper.updateById(existing);
        } else {
            SystemConfig config = new SystemConfig();
            config.setConfigKey(key);
            config.setConfigValue(value);
            config.setDescription(description);
            configMapper.insert(config);
        }
    }

    private Map<String, String> getAllConfigs() {
        var list = configMapper.selectList(null);
        var map = new java.util.HashMap<String, String>();
        for (SystemConfig config : list) {
            if (config.getConfigValue() != null) {
                map.put(config.getConfigKey(), config.getConfigValue());
            }
        }
        return map;
    }
}
