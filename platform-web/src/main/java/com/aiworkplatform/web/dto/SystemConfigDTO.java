package com.aiworkplatform.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 系统配置 DTO（前端设置页面使用）
 */
@Data
public class SystemConfigDTO {

    @NotBlank(message = "工作目录不能为空")
    private String workspaceBasePath;

    @NotBlank(message = "Claude CLI 路径不能为空")
    private String claudeCliPath;
}
