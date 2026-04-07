package com.aiworkplatform.web.controller;

import com.aiworkplatform.common.result.Result;
import com.aiworkplatform.domain.entity.Conversation;
import com.aiworkplatform.domain.entity.Project;
import com.aiworkplatform.service.chat.ChatService;
import com.aiworkplatform.service.orchestrator.OrchestratorConfig;
import com.aiworkplatform.service.project.ProjectService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final ChatService chatService;
    private final OrchestratorConfig orchestratorConfig;

    public ProjectController(ProjectService projectService,
                             ChatService chatService,
                             OrchestratorConfig orchestratorConfig) {
        this.projectService = projectService;
        this.chatService = chatService;
        this.orchestratorConfig = orchestratorConfig;
    }

    @PostMapping
    public Result<Project> createProject(@RequestParam @NotBlank String name,
                                         @RequestParam(required = false) String description) {
        // TODO: 从认证上下文获取 createdBy，暂时硬编码
        Project project = projectService.createProject(
                name, description, orchestratorConfig.getWorkspaceBasePath(), "default-user");
        return Result.success(project);
    }

    @GetMapping
    public Result<List<Project>> listProjects() {
        // TODO: 从认证上下文获取用户
        return Result.success(projectService.listByUser("default-user"));
    }

    @GetMapping("/{projectId}")
    public Result<Project> getProject(@PathVariable String projectId) {
        return Result.success(projectService.getByProjectId(projectId));
    }

    @GetMapping("/{projectId}/conversations")
    public Result<List<Conversation>> getConversations(@PathVariable String projectId) {
        return Result.success(chatService.getHistory(projectId));
    }

    @PostMapping("/{projectId}/chat")
    public Result<Void> chat(@PathVariable String projectId,
                             @RequestParam @NotBlank String message) {
        chatService.handleUserMessage(projectId, message);
        return Result.success();
    }

    @DeleteMapping("/{projectId}")
    public Result<Void> deleteProject(@PathVariable String projectId) {
        projectService.deleteProject(projectId);
        return Result.success();
    }
}
