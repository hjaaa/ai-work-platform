package com.aiworkplatform.web.controller;

import com.aiworkplatform.common.result.Result;
import com.aiworkplatform.service.deploy.DeployConfig;
import com.aiworkplatform.service.deploy.DeployService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class DeployController {

    private final DeployService deployService;
    private final DeployConfig deployConfig;

    public DeployController(DeployService deployService, DeployConfig deployConfig) {
        this.deployService = deployService;
        this.deployConfig = deployConfig;
    }

    /**
     * 触发部署
     */
    @PostMapping("/projects/{projectId}/deploy")
    public Result<Void> deploy(@PathVariable String projectId,
                               @RequestParam @NotBlank String serverName) {
        deployService.deploy(projectId, serverName);
        return Result.success();
    }

    /**
     * 获取可用的部署服务器列表
     */
    @GetMapping("/servers")
    public Result<List<DeployConfig.ServerConfig>> listServers() {
        return Result.success(deployConfig.getServers());
    }
}
