package com.aiworkplatform.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("generation")
public class Generation {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String projectId;
    private String type;
    private String prompt;
    private String generatedFiles;
    private String prdContent;
    private String status;
    private Integer durationMs;
    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableLogic
    private Integer deleted;
}
