package com.aiworkplatform.web.dto;

import com.aiworkplatform.domain.entity.Skill;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SkillVO {

    private String skillId;
    private String name;
    private String description;
    private String scope;
    private String projectId;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static SkillVO from(Skill skill) {
        SkillVO vo = new SkillVO();
        vo.setSkillId(skill.getSkillId());
        vo.setName(skill.getName());
        vo.setDescription(skill.getDescription());
        vo.setScope(skill.getScope());
        vo.setProjectId(skill.getProjectId());
        vo.setCreatedBy(skill.getCreatedBy());
        vo.setCreatedAt(skill.getCreatedAt());
        vo.setUpdatedAt(skill.getUpdatedAt());
        return vo;
    }
}
