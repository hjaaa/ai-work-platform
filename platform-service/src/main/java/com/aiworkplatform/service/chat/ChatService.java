package com.aiworkplatform.service.chat;

import com.aiworkplatform.domain.entity.Conversation;

import java.util.List;

/**
 * 对话服务接口
 */
public interface ChatService {

    /**
     * 在指定线程中处理用户消息
     */
    void handleThreadMessage(String threadId, String content);

    /**
     * 在指定线程中处理用户消息（附带文件路径）
     */
    void handleThreadMessage(String threadId, String content, java.util.List<String> filePaths);

    /**
     * 获取线程的历史对话
     */
    List<Conversation> getHistoryByThread(String threadId);

    /**
     * 取消指定线程正在进行的 AI 生成
     */
    void cancelThread(String threadId);

    /**
     * 处理用户发送的对话消息（旧接口，路由到项目默认线程）
     */
    @Deprecated
    void handleUserMessage(String projectId, String content);

    /**
     * 获取项目的历史对话（旧接口，返回所有线程的合并消息）
     */
    @Deprecated
    List<Conversation> getHistory(String projectId);
}
