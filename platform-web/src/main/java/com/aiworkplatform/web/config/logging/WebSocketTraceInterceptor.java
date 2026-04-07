package com.aiworkplatform.web.config.logging;

import org.slf4j.MDC;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * WebSocket STOMP 消息的 traceId 注入
 * WebSocket 不走 Servlet Filter，需要单独处理
 */
@Component
public class WebSocketTraceInterceptor implements ChannelInterceptor {

    private static final String TRACE_ID_KEY = "traceId";

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);

        // 从 STOMP header 中获取，或自动生成
        String traceId = accessor.getFirstNativeHeader("X-Trace-Id");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put(TRACE_ID_KEY, traceId);

        return message;
    }

    @Override
    public void afterSendCompletion(Message<?> message, MessageChannel channel,
                                    boolean sent, Exception ex) {
        MDC.remove(TRACE_ID_KEY);
    }
}
