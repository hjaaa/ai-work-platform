package com.aiworkplatform.service.thread;

import com.aiworkplatform.domain.entity.ChatThread;

import java.util.List;

/**
 * 对话线程服务接口
 */
public interface ChatThreadService {

    /**
     * 创建新线程
     *
     * @param projectId 所属项目 ID
     * @return 创建的线程
     */
    ChatThread createThread(String projectId);

    /**
     * 获取项目下的线程列表（按 updatedAt 倒序）
     */
    List<ChatThread> listByProject(String projectId);

    /**
     * 根据 threadId 获取线程
     */
    ChatThread getByThreadId(String threadId);

    /**
     * 更新线程标题
     */
    void updateTitle(String threadId, String title);

    /**
     * 逻辑删除线程及其关联的消息
     */
    void deleteThread(String threadId);

    /**
     * 获取或创建项目的默认线程（用于旧 API 兼容）
     * 返回项目下最新的线程，如果没有则自动创建
     */
    ChatThread getOrCreateDefaultThread(String projectId);

    /**
     * 更新线程的 Claude CLI session ID
     */
    void updateClaudeSessionId(String threadId, String sessionId);
}
