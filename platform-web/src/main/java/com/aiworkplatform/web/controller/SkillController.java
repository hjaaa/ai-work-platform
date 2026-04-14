package com.aiworkplatform.web.controller;

import com.aiworkplatform.common.result.Result;
import com.aiworkplatform.domain.entity.Skill;
import com.aiworkplatform.domain.enums.SkillScope;
import com.aiworkplatform.service.skill.SkillService;
import com.aiworkplatform.web.dto.CreateSkillRequest;
import com.aiworkplatform.web.dto.SkillVO;
import com.aiworkplatform.web.dto.UpdateSkillRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillService skillService;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    @GetMapping
    public Result<List<SkillVO>> listSkills(
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false, defaultValue = "false") boolean includeSystem) {
        SkillScope skillScope = null;
        if (scope != null) {
            skillScope = SkillScope.valueOf(scope);
        }
        List<SkillVO> vos = skillService.listSkills(skillScope, projectId, includeSystem)
                .stream()
                .map(SkillVO::from)
                .toList();
        return Result.success(vos);
    }

    @PostMapping
    public Result<SkillVO> createSkill(@RequestBody @Valid CreateSkillRequest request) {
        // TODO: 从认证上下文获取 createdBy，暂时硬编码
        Skill skill = skillService.createSkill(
                request.getName(),
                request.getDescription(),
                SkillScope.valueOf(request.getScope()),
                request.getProjectId(),
                "default",
                null // 个人 skill 使用 user.home，由 service 处理
        );
        return Result.success(SkillVO.from(skill));
    }

    @PutMapping("/{skillId}")
    public Result<Void> updateSkill(
            @PathVariable String skillId,
            @RequestBody @Valid UpdateSkillRequest request) {
        skillService.updateSkill(skillId, request.getName(), request.getDescription(), null);
        return Result.success(null);
    }

    @DeleteMapping("/{skillId}")
    public Result<Void> deleteSkill(@PathVariable String skillId) {
        skillService.deleteSkill(skillId, null);
        return Result.success(null);
    }
}
