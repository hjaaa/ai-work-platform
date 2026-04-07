package com.aiworkplatform.web.controller;

import com.aiworkplatform.common.result.Result;
import com.aiworkplatform.domain.entity.Deployment;
import com.aiworkplatform.domain.entity.Generation;
import com.aiworkplatform.service.project.GenerationQueryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}")
public class GenerationController {

    private final GenerationQueryService generationQueryService;

    public GenerationController(GenerationQueryService generationQueryService) {
        this.generationQueryService = generationQueryService;
    }

    @GetMapping("/generations")
    public Result<List<Generation>> listGenerations(@PathVariable String projectId) {
        return Result.success(generationQueryService.listByProjectId(projectId));
    }

    @GetMapping("/deployments")
    public Result<List<Deployment>> listDeployments(@PathVariable String projectId) {
        return Result.success(generationQueryService.listDeploymentsByProjectId(projectId));
    }
}
