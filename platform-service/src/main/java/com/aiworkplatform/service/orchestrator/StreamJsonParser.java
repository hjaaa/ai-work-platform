package com.aiworkplatform.service.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Claude CLI stream-json 输出解析器
 *
 * 关键事件类型：
 * - result：最终结果，包含完整回复和 session_id
 * - assistant：AI 回复片段，包含 text content
 * - system / rate_limit_event 等：忽略
 */
public class StreamJsonParser {

    private static final Logger log = LoggerFactory.getLogger(StreamJsonParser.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 收到 assistant text 内容时的回调 */
    private final Consumer<String> onAssistantText;

    public StreamJsonParser(Consumer<String> onAssistantText) {
        this.onAssistantText = onAssistantText;
    }

    /**
     * 解析 stream-json 的一行输出
     *
     * @param line      JSON 行
     * @param fullOutput 累积完整回复（result 事件会覆盖）
     * @param sessionId  累积 session ID（result 事件会更新）
     */
    public void parseLine(String line, StringBuilder fullOutput, AtomicReference<String> sessionId) {
        if (line == null || line.isBlank()) {
            return;
        }

        try {
            JsonNode node = objectMapper.readTree(line);
            String type = node.path("type").asText("");

            if ("result".equals(type)) {
                handleResultEvent(node, fullOutput, sessionId);
            } else if ("assistant".equals(type)) {
                handleAssistantEvent(node);
            }
            // 其他事件（system、rate_limit_event 等）忽略

        } catch (Exception e) {
            // 非 JSON 行或解析失败，跳过（容错）
            log.debug("stream-json 解析跳过: {}",
                    line.length() > 100 ? line.substring(0, 100) + "..." : line);
        }
    }

    private void handleResultEvent(JsonNode node, StringBuilder fullOutput, AtomicReference<String> sessionId) {
        String result = node.path("result").asText("");
        String sid = node.path("session_id").asText(null);

        if (!result.isEmpty()) {
            fullOutput.setLength(0);
            fullOutput.append(result);
        }
        if (sid != null) {
            sessionId.set(sid);
        }
    }

    private void handleAssistantEvent(JsonNode node) {
        JsonNode content = node.path("message").path("content");
        if (content.isArray()) {
            for (JsonNode item : content) {
                if ("text".equals(item.path("type").asText())) {
                    String text = item.path("text").asText("");
                    if (!text.isEmpty()) {
                        onAssistantText.accept(text);
                    }
                }
            }
        }
    }
}
