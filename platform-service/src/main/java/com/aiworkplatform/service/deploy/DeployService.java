package com.aiworkplatform.service.deploy;

import com.aiworkplatform.domain.entity.Deployment;

/**
 * 部署服务接口
 */
public interface DeployService {

    /**
     * 执行完整部署流程
     */
    void deploy(String projectId, String targetServerName);

    /**
     * 回滚到上一个成功的版本
     */
    void rollback(String projectId, DeployConfig.ServerConfig server, Deployment failedDeployment);
}
