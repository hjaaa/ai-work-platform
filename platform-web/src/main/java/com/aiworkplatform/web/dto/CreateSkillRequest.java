package com.aiworkplatform.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class CreateSkillRequest {

    @NotBlank(message = "Skill 名称不能为空")
    private String name;

    /** Skill 描述/内容（markdown 格式） */
    private String description;

    /** 作用域：PERSONAL 或 PROJECT */
    @NotNull(message = "scope 不能为空")
    @Pattern(regexp = "PERSONAL|PROJECT", message = "scope 只能为 PERSONAL 或 PROJECT")
    private String scope;

    /** 关联项目 ID（scope=PROJECT 时必填） */
    private String projectId;
}
