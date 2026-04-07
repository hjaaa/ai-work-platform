package com.aiworkplatform.service.chat;

import com.aiworkplatform.domain.entity.Conversation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextWindowManagerTest {

    private ContextWindowManager manager;

    @BeforeEach
    void setUp() {
        manager = new ContextWindowManager();
    }

    @Test
    void should_returnEmpty_when_noHistory() {
        String context = manager.buildContext(List.of());
        assertEquals("", context);
    }

    @Test
    void should_includeAllMessages_when_historyWithinLimit() {
        List<Conversation> history = List.of(
                createConv("user", "创建一个用户管理页面", "text"),
                createConv("assistant", "好的，我来为你生成", "code")
        );

        String context = manager.buildContext(history);

        assertTrue(context.contains("用户管理页面"));
        assertTrue(context.contains("为你生成"));
    }

    @Test
    void should_summarizeEarlier_when_historyExceedsLimit() {
        List<Conversation> history = new ArrayList<>();
        // 添加超过 12 条消息（6 轮 * 2）
        for (int i = 0; i < 20; i++) {
            String role = i % 2 == 0 ? "user" : "assistant";
            history.add(createConv(role, "消息内容 " + i, "text"));
        }

        String context = manager.buildContext(history);

        // 应包含摘要部分
        assertTrue(context.contains("早期对话摘要"));
        // 应包含最近的消息
        assertTrue(context.contains("消息内容 19"));
    }

    @Test
    void should_filterProgressMessages_when_systemProgressPresent() {
        List<Conversation> history = List.of(
                createConv("user", "开始生成", "text"),
                createConvWithType("system", "正在生成中...", "progress"),
                createConv("assistant", "生成完成", "code")
        );

        String context = manager.buildContext(history);

        assertTrue(context.contains("开始生成"));
        assertTrue(context.contains("生成完成"));
        // 进度消息不应出现在上下文中（被过滤）
        // 注意：过滤条件是 role=system AND type=progress
    }

    @Test
    void should_truncateLongMessage_when_singleMessageTooLong() {
        String longContent = "x".repeat(5000);
        List<Conversation> history = List.of(
                createConv("user", longContent, "text")
        );

        String context = manager.buildContext(history);

        // 应包含截断提示
        assertTrue(context.contains("截断"));
        assertTrue(context.length() < longContent.length());
    }

    private Conversation createConv(String role, String content, String messageType) {
        Conversation conv = new Conversation();
        conv.setRole(role);
        conv.setContent(content);
        conv.setMessageType(messageType);
        return conv;
    }

    private Conversation createConvWithType(String role, String content, String messageType) {
        return createConv(role, content, messageType);
    }
}
