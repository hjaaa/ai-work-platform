package com.aiworkplatform.service.deploy;

import com.aiworkplatform.common.exception.BusinessException;
import com.aiworkplatform.common.util.ProcessExecutor;
import com.aiworkplatform.domain.entity.Deployment;
import com.aiworkplatform.domain.entity.Project;
import com.aiworkplatform.domain.enums.DeploymentStatus;
import com.aiworkplatform.domain.mapper.DeploymentMapper;
import com.aiworkplatform.service.chat.MessagePushService;
import com.aiworkplatform.service.project.ProjectService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Docker 部署服务
 * 负责：后端打包 → 前端构建 → Docker 镜像构建 → 推送/传输 → 远程启动 → 健康检查
 */
@Service
public class DeployService {

    private static final Logger log = LoggerFactory.getLogger(DeployService.class);
    private static final DateTimeFormatter TAG_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final DeployConfig deployConfig;
    private final DeploymentMapper deploymentMapper;
    private final ProjectService projectService;
    private final MessagePushService messagePushService;
    private final HealthCheckService healthCheckService;

    public DeployService(DeployConfig deployConfig,
                         DeploymentMapper deploymentMapper,
                         ProjectService projectService,
                         MessagePushService messagePushService,
                         HealthCheckService healthCheckService) {
        this.deployConfig = deployConfig;
        this.deploymentMapper = deploymentMapper;
        this.projectService = projectService;
        this.messagePushService = messagePushService;
        this.healthCheckService = healthCheckService;
    }

    /**
     * 执行完整部署流程
     */
    @Async
    public void deploy(String projectId, String targetServerName) {
        Project project = projectService.getByProjectId(projectId);
        Path projectDir = Path.of(project.getWorkspacePath());

        DeployConfig.ServerConfig server = findServer(targetServerName);
        String imageTag = "v" + LocalDateTime.now().format(TAG_FORMAT);
        String imageName = deployConfig.getDockerRegistry() + "/" + projectId + ":" + imageTag;

        // 创建部署记录
        Deployment deployment = new Deployment();
        deployment.setProjectId(projectId);
        deployment.setImageTag(imageTag);
        deployment.setTargetServer(server.getHost());
        deployment.setStatus(DeploymentStatus.BUILDING.getValue());
        deploymentMapper.insert(deployment);

        try {
            // Step 1: 后端打包
            updateDeployStatus(deployment, DeploymentStatus.BUILDING);
            messagePushService.pushProgress(projectId, "正在打包后端（mvn package）...");
            executeCommand(projectDir.resolve("backend"), "mvn", "package", "-DskipTests", "-q");

            // Step 2: 前端构建
            messagePushService.pushProgress(projectId, "正在构建前端（npm run build）...");
            executeCommand(projectDir.resolve("frontend"), "npm", "run", "build");

            // Step 3: Docker 镜像构建
            messagePushService.pushProgress(projectId, "正在构建 Docker 镜像...");
            executeCommand(projectDir, "docker", "build", "-t", imageName, ".");

            // Step 4: 推送镜像
            updateDeployStatus(deployment, DeploymentStatus.PUSHING);
            messagePushService.pushProgress(projectId, "正在推送 Docker 镜像...");
            executeCommand(projectDir, "docker", "push", imageName);

            // Step 5: 远程部署
            updateDeployStatus(deployment, DeploymentStatus.DEPLOYING);
            messagePushService.pushProgress(projectId, "正在远程部署到 " + server.getHost() + "...");
            remoteDeployViaSSH(server, projectId, imageName);

            // Step 6: 健康检查
            messagePushService.pushProgress(projectId, "正在执行健康检查...");
            String deployUrl = "http://" + server.getHost() + ":8080";
            boolean healthy = healthCheckService.waitForHealthy(deployUrl, projectId);

            if (healthy) {
                deployment.setStatus(DeploymentStatus.RUNNING.getValue());
                deployment.setDeployUrl(deployUrl);
                deploymentMapper.updateById(deployment);
                messagePushService.pushProgress(projectId, "部署成功！访问地址: " + deployUrl);
            } else {
                // 健康检查失败，执行回滚
                messagePushService.pushProgress(projectId, "健康检查失败，正在回滚...");
                rollback(projectId, server, deployment);
            }

        } catch (Exception e) {
            log.error("部署失败: projectId={}, server={}", projectId, server.getHost(), e);
            deployment.setStatus(DeploymentStatus.FAILED.getValue());
            deployment.setErrorMessage(e.getMessage());
            deploymentMapper.updateById(deployment);
            messagePushService.pushAssistantMessage(projectId, "部署失败: " + e.getMessage());
        }
    }

    /**
     * 回滚到上一个成功的版本
     */
    public void rollback(String projectId, DeployConfig.ServerConfig server, Deployment failedDeployment) {
        // 查找上一个成功的部署
        Deployment lastSuccess = deploymentMapper.selectOne(
                new LambdaQueryWrapper<Deployment>()
                        .eq(Deployment::getProjectId, projectId)
                        .eq(Deployment::getStatus, DeploymentStatus.RUNNING.getValue())
                        .ne(Deployment::getId, failedDeployment.getId())
                        .orderByDesc(Deployment::getCreatedAt)
                        .last("LIMIT 1"));

        if (lastSuccess != null) {
            String previousImage = deployConfig.getDockerRegistry() + "/" + projectId + ":" + lastSuccess.getImageTag();
            try {
                remoteDeployViaSSH(server, projectId, previousImage);
                failedDeployment.setStatus(DeploymentStatus.ROLLED_BACK.getValue());
                deploymentMapper.updateById(failedDeployment);
                messagePushService.pushProgress(projectId, "已回滚到版本 " + lastSuccess.getImageTag());
            } catch (Exception e) {
                log.error("回滚失败: projectId={}", projectId, e);
                failedDeployment.setStatus(DeploymentStatus.FAILED.getValue());
                failedDeployment.setErrorMessage("部署和回滚均失败: " + e.getMessage());
                deploymentMapper.updateById(failedDeployment);
                messagePushService.pushAssistantMessage(projectId, "回滚也失败了，请人工介入: " + e.getMessage());
            }
        } else {
            failedDeployment.setStatus(DeploymentStatus.FAILED.getValue());
            failedDeployment.setErrorMessage("健康检查失败且无可回滚版本");
            deploymentMapper.updateById(failedDeployment);
            messagePushService.pushAssistantMessage(projectId, "健康检查失败，且没有可回滚的历史版本");
        }
    }

    private void remoteDeployViaSSH(DeployConfig.ServerConfig server, String projectId, String imageName) {
        String sshCommand = String.format(
                "docker pull %s && docker stop %s 2>/dev/null; docker rm %s 2>/dev/null; " +
                        "docker run -d --name %s -p 8080:8080 --restart=unless-stopped %s",
                imageName, projectId, projectId, projectId, imageName);

        String[] cmd;
        if (server.getPrivateKeyPath() != null && !server.getPrivateKeyPath().isEmpty()) {
            cmd = new String[]{"ssh", "-i", server.getPrivateKeyPath(),
                    "-o", "StrictHostKeyChecking=no",
                    server.getUsername() + "@" + server.getHost(),
                    sshCommand};
        } else {
            cmd = new String[]{"ssh",
                    "-o", "StrictHostKeyChecking=no",
                    server.getUsername() + "@" + server.getHost(),
                    sshCommand};
        }

        executeCommand(Path.of("/tmp"), cmd);
    }

    private DeployConfig.ServerConfig findServer(String serverName) {
        return deployConfig.getServers().stream()
                .filter(s -> s.getName().equals(serverName))
                .findFirst()
                .orElseThrow(() -> new BusinessException("未找到服务器配置: " + serverName));
    }

    private void updateDeployStatus(Deployment deployment, DeploymentStatus status) {
        deployment.setStatus(status.getValue());
        deploymentMapper.updateById(deployment);
    }

    private void executeCommand(Path workDir, String... command) {
        ProcessExecutor.execute(workDir, 10, command);
    }
}
