package com.aiworkplatform.service.document;

/**
 * PRD 生成服务接口
 */
public interface PrdGenerateService {

    /**
     * 初始生成 PRD（第一轮对话）
     */
    void generateInitialPrd(String projectId, String userDescription);

    /**
     * 根据用户回答细化 PRD
     */
    void refinePrd(String projectId, String currentPrd, String userAnswer);

    /**
     * 最终确认 PRD
     */
    void finalizePrd(String projectId, String currentPrd);
}
