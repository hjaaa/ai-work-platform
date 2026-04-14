package com.aiworkplatform.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateProjectRequest {

    @NotBlank(message = "项目名称不能为空")
    private String name;

    /** 项目类型: git / local，默认 git */
    private String projectType;

    /** Git 仓库地址（git 类型必填） */
    private String gitUrl;

    /** 默认分支（git 类型可选，默认 main） */
    private String defaultBranch;

    /** 本地项目路径（local 类型必填） */
    private String localPath;

    private String description;
}
