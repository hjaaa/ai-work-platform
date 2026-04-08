package com.aiworkplatform.web.controller;

import com.aiworkplatform.common.result.Result;
import com.aiworkplatform.service.config.SystemConfigService;
import com.aiworkplatform.service.orchestrator.OrchestratorConfig;
import com.aiworkplatform.web.dto.SystemConfigDTO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SystemConfigService systemConfigService;
    private final OrchestratorConfig orchestratorConfig;

    public SettingsController(SystemConfigService systemConfigService,
                              OrchestratorConfig orchestratorConfig) {
        this.systemConfigService = systemConfigService;
        this.orchestratorConfig = orchestratorConfig;
    }

    @GetMapping
    public Result<SystemConfigDTO> getSettings() {
        SystemConfigDTO dto = new SystemConfigDTO();
        dto.setWorkspaceBasePath(orchestratorConfig.getWorkspaceBasePath());
        dto.setClaudeCliPath(orchestratorConfig.getClaudeCliPath());
        return Result.success(dto);
    }

    @PutMapping
    public Result<SystemConfigDTO> updateSettings(@RequestBody @Valid SystemConfigDTO request) {
        systemConfigService.saveConfig(request.getWorkspaceBasePath(), request.getClaudeCliPath());

        SystemConfigDTO dto = new SystemConfigDTO();
        dto.setWorkspaceBasePath(orchestratorConfig.getWorkspaceBasePath());
        dto.setClaudeCliPath(orchestratorConfig.getClaudeCliPath());
        return Result.success(dto);
    }
}
