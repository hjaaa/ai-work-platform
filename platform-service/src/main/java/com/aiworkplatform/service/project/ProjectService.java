package com.aiworkplatform.service.project;

import com.aiworkplatform.domain.entity.Project;
import com.aiworkplatform.domain.enums.ProjectStatus;

import java.util.List;

/**
 * 项目服务接口
 */
public interface ProjectService {

    /**
     * 创建新项目
     *
     * @param projectType 项目类型: git / local
     * @param localPath 本地项目路径（local 类型时必填）
     */
    Project createProject(String name, String projectType, String gitUrl, String defaultBranch,
                          String localPath, String description, String workspaceBasePath, String createdBy);

    /**
     * 根据 projectId 查询项目
     */
    Project getByProjectId(String projectId);

    /**
     * 查询用户的项目列表
     */
    List<Project> listByUser(String createdBy);

    /**
     * 更新项目基本信息
     */
    Project updateProject(String projectId, String name, String gitUrl, String defaultBranch,
                          String localPath, String description);

    /**
     * 删除项目（逻辑删除）
     */
    void deleteProject(String projectId);

    /**
     * 更新项目状态
     */
    void updateStatus(String projectId, ProjectStatus from, ProjectStatus to);

    /**
     * 更新 clone 成功后的代码路径，并切换状态为 ACTIVE
     */
    void markCloneSuccess(String projectId, String codePath);

    /**
     * 标记 clone 失败，记录错误信息
     */
    void markCloneFailed(String projectId, String errorMessage);

    /**
     * 直接更新项目实体（用于 retryClone 等需要批量更新多个字段的场景）
     */
    void updateProjectEntity(Project project);
}
