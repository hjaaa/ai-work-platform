package com.aiworkplatform.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("deployment")
public class Deployment {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String projectId;
    private String imageTag;
    private String targetServer;
    private String status;
    private String deployUrl;
    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableLogic
    private Integer deleted;
}
