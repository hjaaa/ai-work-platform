package com.aiworkplatform.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class UpdateSpecRequest {

    /** 规范名称（必填） */
    @NotBlank(message = "规范名称不能为空")
    private String name;

    /** 规范类型：UI / FRONTEND / BACKEND */
    @NotBlank(message = "规范类型不能为空")
    @Pattern(regexp = "UI|FRONTEND|BACKEND", message = "规范类型只能为 UI、FRONTEND 或 BACKEND")
    private String specType;

    /** 规范内容（Markdown 格式） */
    private String content;
}
