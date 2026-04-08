package com.aiworkplatform.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话线程实体
 * 一个项目下可以有多个线程，每个线程包含多条对话消息
 */
@Data
@TableName("chat_thread")
public class ChatThread {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String threadId;
    private String projectId;
    private String title;

    /**
     * Claude CLI 会话 ID，首次对话时生成，后续通过 --resume 复用上下文
     */
    private String claudeSessionId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
