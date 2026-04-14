package com.aiworkplatform.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 对话消息实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("conversation")
public class Conversation extends BaseEntity {

    /** 项目标识 */
    private String projectId;

    /** 所属线程标识 */
    private String threadId;

    /** 角色: user/assistant/system */
    private String role;

    /** 消息内容 */
    private String content;

    /** 消息类型: text/file/code/progress */
    private String messageType;
}
