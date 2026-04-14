package com.aiworkplatform.web.controller;

import com.aiworkplatform.common.exception.BusinessException;
import com.aiworkplatform.common.result.Result;
import com.aiworkplatform.domain.entity.Conversation;
import com.aiworkplatform.domain.entity.Project;
import com.aiworkplatform.domain.enums.ProjectType;
import com.aiworkplatform.service.chat.ChatService;
import com.aiworkplatform.service.git.GitService;
import com.aiworkplatform.service.orchestrator.OrchestratorConfig;
import com.aiworkplatform.service.project.ProjectService;
import com.aiworkplatform.web.dto.CreateProjectRequest;
import com.aiworkplatform.web.dto.UpdateProjectRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final ChatService chatService;
    private final GitService gitService;
    private final OrchestratorConfig orchestratorConfig;

    public ProjectController(ProjectService projectService,
                             ChatService chatService,
                             GitService gitService,
                             OrchestratorConfig orchestratorConfig) {
        this.projectService = projectService;
        this.chatService = chatService;
        this.gitService = gitService;
        this.orchestratorConfig = orchestratorConfig;
    }

    @PostMapping
    public Result<Project> createProject(@RequestBody @Valid CreateProjectRequest request) {
        String type = StringUtils.hasText(request.getProjectType()) ? request.getProjectType() : ProjectType.GIT.getValue();

        // 按项目类型校验必填字段
        if (ProjectType.GIT.getValue().equals(type)) {
            if (!StringUtils.hasText(request.getGitUrl())) {
                throw new BusinessException("Git 项目必须填写仓库地址");
            }
        } else if (ProjectType.LOCAL.getValue().equals(type)) {
            if (!StringUtils.hasText(request.getLocalPath())) {
                throw new BusinessException("本地项目必须填写项目路径");
            }
        } else {
            throw new BusinessException("不支持的项目类型: " + type);
        }

        // TODO: 从认证上下文获取 createdBy，暂时硬编码
        Project project = projectService.createProject(
                request.getName(), type, request.getGitUrl(), request.getDefaultBranch(),
                request.getLocalPath(), request.getDescription(),
                orchestratorConfig.getWorkspaceBasePath(), "default-user");

        // 仅 Git 项目需要异步拉取代码
        if (ProjectType.GIT.getValue().equals(type)) {
            gitService.cloneRepository(project.getProjectId());
        }
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

    /**
     * @deprecated 请使用 GET /api/threads/{threadId}/messages
     */
    @Deprecated
    @GetMapping("/{projectId}/conversations")
    public Result<List<Conversation>> getConversations(@PathVariable String projectId) {
        return Result.success(chatService.getHistory(projectId));
    }

    /**
     * @deprecated 请使用 POST /api/threads/{threadId}/chat
     */
    @Deprecated
    @PostMapping("/{projectId}/chat")
    public Result<Void> chat(@PathVariable String projectId,
                             @RequestParam @NotBlank String message) {
        chatService.handleUserMessage(projectId, message);
        return Result.success();
    }

    @PutMapping("/{projectId}")
    public Result<Project> updateProject(@PathVariable String projectId,
                                         @RequestBody @Valid UpdateProjectRequest request) {
        Project project = projectService.updateProject(
                projectId, request.getName(), request.getGitUrl(),
                request.getDefaultBranch(), request.getLocalPath(), request.getDescription());
        return Result.success(project);
    }

    @DeleteMapping("/{projectId}")
    public Result<Void> deleteProject(@PathVariable String projectId) {
        projectService.deleteProject(projectId);
        return Result.success();
    }

    @PostMapping("/{projectId}/retry-clone")
    public Result<Void> retryClone(@PathVariable String projectId) {
        gitService.retryClone(projectId);
        return Result.success();
    }
}
