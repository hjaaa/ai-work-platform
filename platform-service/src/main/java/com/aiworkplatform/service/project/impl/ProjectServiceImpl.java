package com.aiworkplatform.service.project.impl;

import com.aiworkplatform.common.exception.BusinessException;
import com.aiworkplatform.domain.entity.Project;
import com.aiworkplatform.domain.enums.ProjectStatus;
import com.aiworkplatform.domain.mapper.ProjectMapper;
import com.aiworkplatform.service.project.ProjectService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class ProjectServiceImpl implements ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectServiceImpl.class);

    private final ProjectMapper projectMapper;

    public ProjectServiceImpl(ProjectMapper projectMapper) {
        this.projectMapper = projectMapper;
    }

    @Override
    public Project createProject(String name, String gitUrl, String defaultBranch,
                                 String description, String workspaceBasePath, String createdBy) {
        String projectId = "proj-" + UUID.randomUUID().toString().substring(0, 8);

        // 创建工作区目录
        Path basePath = Path.of(workspaceBasePath);
        if (!Files.exists(basePath)) {
            try {
                Files.createDirectories(basePath);
            } catch (Exception e) {
                log.error("创建工作区基础目录失败: path={}", basePath, e);
                throw new BusinessException("创建工作区基础目录失败，请检查路径权限: " + basePath);
            }
        }
        Path workspacePath = basePath.resolve(projectId);
        try {
            Files.createDirectory(workspacePath);
        } catch (Exception e) {
            log.error("创建项目工作区失败: projectId={}, path={}", projectId, workspacePath, e);
            throw new BusinessException("创建项目工作区失败: " + workspacePath);
        }

        Project project = new Project();
        project.setProjectId(projectId);
        project.setName(name);
        project.setGitUrl(gitUrl);
        project.setDefaultBranch(StringUtils.hasText(defaultBranch) ? defaultBranch : "main");
        project.setDescription(description);
        project.setWorkspacePath(workspacePath.toString());
        project.setStatus(ProjectStatus.CREATING.getValue());
        project.setCreatedBy(createdBy);

        projectMapper.insert(project);
        log.info("项目已创建: projectId={}, name={}, gitUrl={}, createdBy={}", projectId, name, gitUrl, createdBy);
        return project;
    }

    @Override
    public Project getByProjectId(String projectId) {
        Project project = projectMapper.selectOne(
                new LambdaQueryWrapper<Project>().eq(Project::getProjectId, projectId));
        if (project == null) {
            throw new BusinessException(404, "项目不存在: " + projectId);
        }
        return project;
    }

    @Override
    public List<Project> listByUser(String createdBy) {
        return projectMapper.selectList(
                new LambdaQueryWrapper<Project>()
                        .eq(Project::getCreatedBy, createdBy)
                        .orderByDesc(Project::getCreatedAt));
    }

    @Override
    public Project updateProject(String projectId, String name, String gitUrl, String defaultBranch, String description) {
        Project project = getByProjectId(projectId);
        project.setName(name);
        project.setGitUrl(gitUrl);
        project.setDefaultBranch(StringUtils.hasText(defaultBranch) ? defaultBranch : "main");
        project.setDescription(description);
        projectMapper.updateById(project);
        log.info("项目已更新: projectId={}, name={}", projectId, name);
        return project;
    }

    @Override
    public void deleteProject(String projectId) {
        Project project = getByProjectId(projectId);

        // 清理磁盘上的工作区目录（含 clone 的代码）
        String workspacePath = project.getWorkspacePath();
        if (StringUtils.hasText(workspacePath)) {
            Path dirPath = Path.of(workspacePath);
            if (Files.exists(dirPath)) {
                try (Stream<Path> walk = Files.walk(dirPath)) {
                    walk.sorted(Comparator.reverseOrder())
                            .forEach(p -> {
                                try { Files.delete(p); } catch (IOException e) {
                                    log.warn("清理项目文件失败: {}", p, e);
                                }
                            });
                    log.info("项目工作区已清理: projectId={}, path={}", projectId, workspacePath);
                } catch (IOException e) {
                    log.warn("遍历项目工作区失败，跳过清理: projectId={}, path={}", projectId, workspacePath, e);
                }
            }
        }

        projectMapper.deleteById(project.getId());
        log.info("项目已删除: projectId={}", projectId);
    }

    @Override
    public void updateStatus(String projectId, ProjectStatus from, ProjectStatus to) {
        Project project = getByProjectId(projectId);
        if (!project.getStatus().equals(from.getValue())) {
            throw new BusinessException("项目状态流转非法: 当前=" + project.getStatus() + ", 期望=" + from.getValue());
        }
        project.setStatus(to.getValue());
        projectMapper.updateById(project);
        log.info("项目状态变更: projectId={}, {} -> {}", projectId, from.getValue(), to.getValue());
    }

    @Override
    public void markCloneSuccess(String projectId, String codePath) {
        Project project = getByProjectId(projectId);
        project.setCodePath(codePath);
        project.setStatus(ProjectStatus.ACTIVE.getValue());
        project.setCloneErrorMessage(null);
        projectMapper.updateById(project);
        log.info("代码拉取成功: projectId={}, codePath={}", projectId, codePath);
    }

    @Override
    public void markCloneFailed(String projectId, String errorMessage) {
        Project project = getByProjectId(projectId);
        project.setStatus(ProjectStatus.FAILED.getValue());
        project.setCloneErrorMessage(errorMessage);
        projectMapper.updateById(project);
        log.error("代码拉取失败: projectId={}, error={}", projectId, errorMessage);
    }

    @Override
    public void updateProjectEntity(Project project) {
        projectMapper.updateById(project);
    }
}
