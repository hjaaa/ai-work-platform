package com.aiworkplatform.service.project;

import com.aiworkplatform.common.exception.BusinessException;
import com.aiworkplatform.domain.entity.Project;
import com.aiworkplatform.domain.enums.ProjectStatus;
import com.aiworkplatform.domain.mapper.ProjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    private final ProjectMapper projectMapper;

    public ProjectService(ProjectMapper projectMapper) {
        this.projectMapper = projectMapper;
    }

    /**
     * 创建新项目
     */
    public Project createProject(String name, String description, String workspaceBasePath, String createdBy) {
        String projectId = "proj-" + UUID.randomUUID().toString().substring(0, 8);

        // 创建工作区目录
        Path workspacePath = Path.of(workspaceBasePath, projectId);
        try {
            Files.createDirectories(workspacePath);
        } catch (Exception e) {
            log.error("创建项目工作区失败: projectId={}, path={}", projectId, workspacePath, e);
            throw new BusinessException("创建项目工作区失败: " + e.getMessage());
        }

        Project project = new Project();
        project.setProjectId(projectId);
        project.setName(name);
        project.setDescription(description);
        project.setWorkspacePath(workspacePath.toString());
        project.setStatus(ProjectStatus.CREATING.getValue());
        project.setCreatedBy(createdBy);

        projectMapper.insert(project);
        log.info("项目已创建: projectId={}, name={}, createdBy={}", projectId, name, createdBy);
        return project;
    }

    /**
     * 根据 projectId 查询项目
     */
    public Project getByProjectId(String projectId) {
        Project project = projectMapper.selectOne(
                new LambdaQueryWrapper<Project>().eq(Project::getProjectId, projectId));
        if (project == null) {
            throw new BusinessException(404, "项目不存在: " + projectId);
        }
        return project;
    }

    /**
     * 查询项目列表
     */
    public List<Project> listByUser(String createdBy) {
        return projectMapper.selectList(
                new LambdaQueryWrapper<Project>()
                        .eq(Project::getCreatedBy, createdBy)
                        .orderByDesc(Project::getCreatedAt));
    }

    /**
     * 删除项目（逻辑删除）
     */
    public void deleteProject(String projectId) {
        Project project = getByProjectId(projectId);
        projectMapper.deleteById(project.getId());
        log.info("项目已删除: projectId={}", projectId);
    }

    /**
     * 更新项目状态
     */
    public void updateStatus(String projectId, ProjectStatus from, ProjectStatus to) {
        Project project = getByProjectId(projectId);
        if (!project.getStatus().equals(from.getValue())) {
            throw new BusinessException("项目状态流转非法: 当前=" + project.getStatus() + ", 期望=" + from.getValue());
        }
        project.setStatus(to.getValue());
        projectMapper.updateById(project);
        log.info("项目状态变更: projectId={}, {} -> {}", projectId, from.getValue(), to.getValue());
    }
}
