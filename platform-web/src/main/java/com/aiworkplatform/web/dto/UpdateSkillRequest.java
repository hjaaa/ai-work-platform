package com.aiworkplatform.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdateSkillRequest {

    @NotBlank(message = "Skill 名称不能为空")
    private String name;

    /** Skill 描述/内容（markdown 格式） */
    private String description;
}
