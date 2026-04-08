package com.aiworkplatform.service.orchestrator;

import lombok.Data;

/**
 * Claude CLI 执行结果
 */
@Data
public class ExecutionResult {

    /** AI 回复的完整文本 */
    private String output;

    /** Claude CLI 的 session ID（从 stream-json result 事件中提取） */
    private String sessionId;

    /** 是否被用户取消 */
    private boolean cancelled;

    public ExecutionResult(String output, String sessionId) {
        this.output = output;
        this.sessionId = sessionId;
        this.cancelled = false;
    }

    public static ExecutionResult cancelled() {
        ExecutionResult result = new ExecutionResult("", null);
        result.cancelled = true;
        return result;
    }
}
