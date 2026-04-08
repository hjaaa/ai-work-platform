package com.aiworkplatform.service.config;

import com.aiworkplatform.domain.entity.SystemConfig;
import com.aiworkplatform.domain.mapper.SystemConfigMapper;
import com.aiworkplatform.service.orchestrator.OrchestratorConfig;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SystemConfigServiceTest {

    @Mock
    private SystemConfigMapper configMapper;

    private OrchestratorConfig orchestratorConfig;
    private SystemConfigService systemConfigService;

    @BeforeEach
    void setUp() {
        orchestratorConfig = new OrchestratorConfig();
        orchestratorConfig.setWorkspaceBasePath("/default/path");
        orchestratorConfig.setClaudeCliPath("/default/claude");
        systemConfigService = new SystemConfigService(configMapper, orchestratorConfig);
    }

    @Test
    void should_load_config_from_db_on_startup() {
        // given
        SystemConfig wsConfig = new SystemConfig();
        wsConfig.setConfigKey("workspace_base_path");
        wsConfig.setConfigValue("/data/projects");

        SystemConfig cliConfig = new SystemConfig();
        cliConfig.setConfigKey("claude_cli_path");
        cliConfig.setConfigValue("/opt/bin/claude");

        when(configMapper.selectList(any())).thenReturn(List.of(wsConfig, cliConfig));

        // when
        systemConfigService.loadConfigFromDb();

        // then
        assertEquals("/data/projects", orchestratorConfig.getWorkspaceBasePath());
        assertEquals("/opt/bin/claude", orchestratorConfig.getClaudeCliPath());
    }

    @Test
    void should_keep_defaults_when_db_is_empty() {
        // given
        when(configMapper.selectList(any())).thenReturn(List.of());

        // when
        systemConfigService.loadConfigFromDb();

        // then
        assertEquals("/default/path", orchestratorConfig.getWorkspaceBasePath());
        assertEquals("/default/claude", orchestratorConfig.getClaudeCliPath());
    }

    @Test
    void should_keep_defaults_when_db_load_fails() {
        // given
        when(configMapper.selectList(any())).thenThrow(new RuntimeException("DB不可用"));

        // when
        systemConfigService.loadConfigFromDb();

        // then — 不抛异常，保留默认值
        assertEquals("/default/path", orchestratorConfig.getWorkspaceBasePath());
        assertEquals("/default/claude", orchestratorConfig.getClaudeCliPath());
    }

    @Test
    void should_insert_config_when_key_not_exists() {
        // given
        when(configMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        // when
        systemConfigService.saveConfig("/new/path", "/new/claude");

        // then
        ArgumentCaptor<SystemConfig> captor = ArgumentCaptor.forClass(SystemConfig.class);
        verify(configMapper, times(2)).insert(captor.capture());

        List<SystemConfig> inserted = captor.getAllValues();
        assertEquals("workspace_base_path", inserted.get(0).getConfigKey());
        assertEquals("/new/path", inserted.get(0).getConfigValue());
        assertEquals("claude_cli_path", inserted.get(1).getConfigKey());
        assertEquals("/new/claude", inserted.get(1).getConfigValue());

        // 内存也应该同步更新
        assertEquals("/new/path", orchestratorConfig.getWorkspaceBasePath());
        assertEquals("/new/claude", orchestratorConfig.getClaudeCliPath());
    }

    @Test
    void should_update_config_when_key_exists() {
        // given
        SystemConfig existing = new SystemConfig();
        existing.setId(1L);
        existing.setConfigKey("workspace_base_path");
        existing.setConfigValue("/old/path");

        SystemConfig existing2 = new SystemConfig();
        existing2.setId(2L);
        existing2.setConfigKey("claude_cli_path");
        existing2.setConfigValue("/old/claude");

        when(configMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(existing)
                .thenReturn(existing2);

        // when
        systemConfigService.saveConfig("/updated/path", "/updated/claude");

        // then
        verify(configMapper, times(2)).updateById(any(SystemConfig.class));
        verify(configMapper, never()).insert(any(SystemConfig.class));

        assertEquals("/updated/path", orchestratorConfig.getWorkspaceBasePath());
        assertEquals("/updated/claude", orchestratorConfig.getClaudeCliPath());
    }

    @Test
    void should_return_effective_config() {
        // when
        var config = systemConfigService.getEffectiveConfig();

        // then
        assertEquals("/default/path", config.get("workspace_base_path"));
        assertEquals("/default/claude", config.get("claude_cli_path"));
    }
}
