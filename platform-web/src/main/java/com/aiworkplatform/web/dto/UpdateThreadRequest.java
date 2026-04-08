package com.aiworkplatform.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdateThreadRequest {

    @NotBlank(message = "线程标题不能为空")
    private String title;
}
