package com.aiworkplatform.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("project")
public class Project {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String projectId;
    private String name;
    private String description;
    private String gitUrl;
    private String defaultBranch;
    private String workspacePath;
    private String codePath;
    private String cloneErrorMessage;
    private String status;
    private String deployUrl;
    private String createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
