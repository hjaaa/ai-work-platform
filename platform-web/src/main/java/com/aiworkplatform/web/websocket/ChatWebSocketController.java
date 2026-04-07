package com.aiworkplatform.web.websocket;

import com.aiworkplatform.service.chat.ChatMessage;
import com.aiworkplatform.service.chat.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

/**
 * WebSocket 对话控制器
 * 客户端发送消息到 /app/chat/{projectId}
 * 服务端推送消息到 /topic/project/{projectId}
 */
@Controller
public class ChatWebSocketController {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketController.class);

    private final ChatService chatService;

    public ChatWebSocketController(ChatService chatService) {
        this.chatService = chatService;
    }

    @MessageMapping("/chat/{projectId}")
    public void handleChatMessage(@DestinationVariable String projectId,
                                  @Payload ChatMessage message) {
        log.info("收到对话消息: projectId={}, contentLength={}", projectId,
                message.getContent() != null ? message.getContent().length() : 0);
        chatService.handleUserMessage(projectId, message.getContent());
    }
}
