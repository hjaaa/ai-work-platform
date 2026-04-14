package com.aiworkplatform.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdateProjectRequest {

    @NotBlank(message = "项目名称不能为空")
    private String name;

    /** Git 仓库地址（git 类型必填） */
    private String gitUrl;

    private String defaultBranch;

    /** 本地项目路径（local 类型必填） */
    private String localPath;

    private String description;
}
