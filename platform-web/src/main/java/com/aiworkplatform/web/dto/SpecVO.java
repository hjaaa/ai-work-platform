package com.aiworkplatform.web.dto;

import com.aiworkplatform.domain.entity.DevSpec;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SpecVO {

    private String specId;
    private String name;
    private String specType;
    private String content;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static SpecVO from(DevSpec spec) {
        SpecVO vo = new SpecVO();
        vo.setSpecId(spec.getSpecId());
        vo.setName(spec.getName());
        vo.setSpecType(spec.getSpecType());
        vo.setContent(spec.getContent());
        vo.setCreatedBy(spec.getCreatedBy());
        vo.setCreatedAt(spec.getCreatedAt());
        vo.setUpdatedAt(spec.getUpdatedAt());
        return vo;
    }
}
