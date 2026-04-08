package com.aiworkplatform.service.git.impl;

import com.aiworkplatform.common.exception.BusinessException;
import com.aiworkplatform.common.util.ProcessExecutor;
import com.aiworkplatform.domain.entity.Project;
import com.aiworkplatform.domain.enums.ProjectStatus;
import com.aiworkplatform.service.chat.MessagePushService;
import com.aiworkplatform.service.git.GitService;
import com.aiworkplatform.service.project.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

@Service
public class GitServiceImpl implements GitService {

    private static final Logger log = LoggerFactory.getLogger(GitServiceImpl.class);
    private static final int CLONE_TIMEOUT_MINUTES = 5;

    private final ProjectService projectService;
    private final MessagePushService messagePushService;
    private final GitService self;

    public GitServiceImpl(ProjectService projectService,
                          MessagePushService messagePushService,
                          @org.springframework.context.annotation.Lazy GitService self) {
        this.projectService = projectService;
        this.messagePushService = messagePushService;
        this.self = self;
    }

    @Override
    public void retryClone(String projectId) {
        Project project = projectService.getByProjectId(projectId);
        if (!ProjectStatus.FAILED.getValue().equals(project.getStatus())) {
            throw new BusinessException("只有拉取失败的项目才能重试，当前状态: " + project.getStatus());
        }

        // 清理残留的 code 目录
        Path codePath = Path.of(project.getWorkspacePath(), "code");
        if (Files.exists(codePath)) {
            try (Stream<Path> walk = Files.walk(codePath)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.delete(p); } catch (IOException e) {
                                log.warn("清理残留文件失败: {}", p, e);
                            }
                        });
            } catch (IOException e) {
                log.error("遍历残留目录失败: projectId={}, codePath={}", projectId, codePath, e);
                throw new BusinessException("清理旧代码目录失败，请手动检查: " + codePath);
            }
        }

        // 重置状态为 CREATING，清除错误信息
        project.setStatus(ProjectStatus.CREATING.getValue());
        project.setCloneErrorMessage(null);
        project.setCodePath(null);
        projectService.updateProjectEntity(project);

        log.info("重试拉取代码: projectId={}, gitUrl={}", projectId, project.getGitUrl());

        // 通过代理调用以确保 @Async 生效
        self.cloneRepository(projectId);
    }

    @Async
    @Override
    public void cloneRepository(String projectId) {
        Project project = projectService.getByProjectId(projectId);
        String gitUrl = project.getGitUrl();
        String branch = project.getDefaultBranch();
        // 代码存储到 workspacePath/code 子目录
        Path codePath = Path.of(project.getWorkspacePath(), "code");

        log.info("开始拉取代码: projectId={}, gitUrl={}, branch={}, codePath={}",
                projectId, gitUrl, branch, codePath);
        messagePushService.pushProgress(projectId, "正在拉取代码...");

        try {
            // git clone --branch {branch} --single-branch {gitUrl} {codePath}
            ProcessExecutor.execute(
                    Path.of(project.getWorkspacePath()),
                    CLONE_TIMEOUT_MINUTES,
                    line -> messagePushService.pushProgress(projectId, line),
                    "git", "clone", "--branch", branch, "--single-branch", gitUrl, codePath.toString()
            );

            projectService.markCloneSuccess(projectId, codePath.toString());
            messagePushService.pushProgress(projectId, "代码拉取完成");

        } catch (Exception e) {
            String errorMsg = e.getMessage();
            // 截取前 500 字符，避免过长
            if (errorMsg != null && errorMsg.length() > 500) {
                errorMsg = errorMsg.substring(0, 500);
            }
            log.error("代码拉取失败: projectId={}, gitUrl={}", projectId, gitUrl, e);
            projectService.markCloneFailed(projectId, errorMsg);
            messagePushService.pushProgress(projectId, "代码拉取失败: " + errorMsg);
        }
    }
}
