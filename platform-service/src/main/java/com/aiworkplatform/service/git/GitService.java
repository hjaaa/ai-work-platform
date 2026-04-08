package com.aiworkplatform.service.git;

/**
 * Git 操作服务接口
 */
public interface GitService {

    /**
     * 异步克隆仓库到项目工作区
     */
    void cloneRepository(String projectId);

    /**
     * 重试克隆：校验状态为 FAILED → 清理残留目录 → 重置状态 → 重新 clone
     */
    void retryClone(String projectId);
}
