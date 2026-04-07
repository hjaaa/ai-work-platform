package com.aiworkplatform.service.chat;

import com.aiworkplatform.domain.entity.Conversation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话上下文窗口管理
 * 控制发送给 Claude Code 的历史对话长度，避免 prompt 过长
 *
 * 策略：
 * 1. 保留最近 N 轮对话（默认 6 轮 = 12 条消息）
 * 2. 如果总字符超过阈值，对早期对话做摘要压缩
 * 3. 系统消息（progress）不纳入上下文
 */
@Component
public class ContextWindowManager {

    private static final int MAX_ROUNDS = 6;
    private static final int MAX_CHARS = 8000;
    private static final int SUMMARY_THRESHOLD = 4000;

    /**
     * 从历史对话中构建上下文，控制在合理长度内
     *
     * @param allHistory 全部历史（按时间正序）
     * @return 用于 prompt 的上下文字符串
     */
    public String buildContext(List<Conversation> allHistory) {
        // 过滤掉系统进度消息
        List<Conversation> relevant = allHistory.stream()
                .filter(c -> !"system".equals(c.getRole()) || !"progress".equals(c.getMessageType()))
                .toList();

        if (relevant.isEmpty()) {
            return "";
        }

        // 取最近 MAX_ROUNDS * 2 条（每轮一问一答）
        int maxMessages = MAX_ROUNDS * 2;
        List<Conversation> recent;
        List<Conversation> earlier;

        if (relevant.size() <= maxMessages) {
            recent = relevant;
            earlier = List.of();
        } else {
            recent = relevant.subList(relevant.size() - maxMessages, relevant.size());
            earlier = relevant.subList(0, relevant.size() - maxMessages);
        }

        StringBuilder context = new StringBuilder();

        // 如果有更早的对话，生成摘要
        if (!earlier.isEmpty()) {
            context.append("## 早期对话摘要\n");
            context.append(summarizeEarlierConversations(earlier));
            context.append("\n\n");
        }

        // 添加最近对话
        context.append("## 最近对话\n");
        for (Conversation conv : recent) {
            String roleLabel = "user".equals(conv.getRole()) ? "用户" : "AI";
            String content = conv.getContent();

            // 对过长的单条消息做截断（保留前后部分）
            if (content.length() > SUMMARY_THRESHOLD) {
                content = content.substring(0, 1500) + "\n...(内容过长已截断)...\n"
                        + content.substring(content.length() - 500);
            }

            context.append(roleLabel).append(": ").append(content).append("\n\n");
        }

        // 最终长度检查
        if (context.length() > MAX_CHARS) {
            return context.substring(context.length() - MAX_CHARS);
        }

        return context.toString();
    }

    /**
     * 对早期对话生成简短摘要
     */
    private String summarizeEarlierConversations(List<Conversation> earlier) {
        List<String> keyPoints = new ArrayList<>();

        for (Conversation conv : earlier) {
            if ("user".equals(conv.getRole())) {
                // 提取用户消息的前 100 字符作为关键点
                String summary = conv.getContent().length() > 100
                        ? conv.getContent().substring(0, 100) + "..."
                        : conv.getContent();
                keyPoints.add("- 用户曾提到: " + summary);
            }
        }

        // 最多保留 5 个关键点
        if (keyPoints.size() > 5) {
            keyPoints = keyPoints.subList(keyPoints.size() - 5, keyPoints.size());
        }

        return String.join("\n", keyPoints);
    }
}
