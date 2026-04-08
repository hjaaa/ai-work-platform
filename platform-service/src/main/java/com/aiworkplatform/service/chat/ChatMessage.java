package com.aiworkplatform.service.chat;

import lombok.Builder;
import lombok.Data;

/**
 * WebSocket 聊天消息
 */
@Data
@Builder
public class ChatMessage {

    private String projectId;
    private String threadId;
    private String role;
    private String content;
    private String messageType;
    private Long timestamp;
}
