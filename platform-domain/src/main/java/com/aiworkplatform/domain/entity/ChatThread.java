package com.aiworkplatform.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 对话线程实体
 * 一个项目下可以有多个线程，每个线程包含多条对话消息
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_thread")
public class ChatThread extends BaseEntity {

    /** 线程业务标识 */
    private String threadId;

    /** 所属项目标识 */
    private String projectId;

    /** 线程标题 */
    private String title;

    /** Claude CLI 会话 ID，首次对话时生成，后续通过 --resume 复用上下文 */
    private String claudeSessionId;
}
