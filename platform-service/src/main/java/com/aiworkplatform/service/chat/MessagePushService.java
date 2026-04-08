package com.aiworkplatform.service.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * WebSocket 消息推送服务
 * 负责向前端推送 AI 回复、进度、测试结果、部署状态等
 */
@Service
public class MessagePushService {

    private static final Logger log = LoggerFactory.getLogger(MessagePushService.class);

    private final SimpMessagingTemplate messagingTemplate;

    public MessagePushService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * 向指定项目的订阅者推送消息
     */
    public void pushToProject(String projectId, ChatMessage message) {
        String destination = "/topic/project/" + projectId;
        log.debug("推送消息到 {}: type={}", destination, message.getMessageType());
        messagingTemplate.convertAndSend(destination, message);
    }

    /**
     * 推送 AI 回复文本
     */
    public void pushAssistantMessage(String projectId, String content) {
        ChatMessage message = ChatMessage.builder()
                .projectId(projectId)
                .role("assistant")
                .content(content)
                .messageType("text")
                .timestamp(System.currentTimeMillis())
                .build();
        pushToProject(projectId, message);
    }

    /**
     * 推送进度信息
     */
    public void pushProgress(String projectId, String content) {
        ChatMessage message = ChatMessage.builder()
                .projectId(projectId)
                .role("system")
                .content(content)
                .messageType("progress")
                .timestamp(System.currentTimeMillis())
                .build();
        pushToProject(projectId, message);
    }

    /**
     * 向指定线程的订阅者推送消息
     */
    public void pushToThread(String threadId, ChatMessage message) {
        String destination = "/topic/thread/" + threadId;
        log.debug("推送消息到 {}: type={}", destination, message.getMessageType());
        messagingTemplate.convertAndSend(destination, message);
    }

    /**
     * 按线程推送 AI 回复
     */
    public void pushAssistantMessageToThread(String threadId, String projectId, String content) {
        ChatMessage message = ChatMessage.builder()
                .projectId(projectId)
                .threadId(threadId)
                .role("assistant")
                .content(content)
                .messageType("text")
                .timestamp(System.currentTimeMillis())
                .build();
        pushToThread(threadId, message);
    }

    /**
     * 按线程推送进度信息
     */
    public void pushProgressToThread(String threadId, String projectId, String content) {
        ChatMessage message = ChatMessage.builder()
                .projectId(projectId)
                .threadId(threadId)
                .role("system")
                .content(content)
                .messageType("progress")
                .timestamp(System.currentTimeMillis())
                .build();
        pushToThread(threadId, message);
    }

}
