package com.aiworkplatform.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LinkSpecRequest {

    /** 目标项目业务主键（必填） */
    @NotBlank(message = "projectId 不能为空")
    private String projectId;
}
