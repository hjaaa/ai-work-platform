package com.aiworkplatform.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 生成记录实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("generation")
public class Generation extends BaseEntity {

    /** 项目标识 */
    private String projectId;

    /** 生成类型: code/prd */
    private String type;

    /** 发送给 Claude Code 的完整 prompt */
    private String prompt;

    /** 生成的文件列表 (JSON) */
    private String generatedFiles;

    /** PRD 生成时的结构化内容 (JSON) */
    private String prdContent;

    /** 状态: running/success/failed */
    private String status;

    /** 耗时毫秒 */
    private Integer durationMs;

    /** 错误信息 */
    private String errorMessage;
}
