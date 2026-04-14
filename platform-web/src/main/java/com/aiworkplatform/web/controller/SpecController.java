package com.aiworkplatform.web.controller;

import com.aiworkplatform.common.result.Result;
import com.aiworkplatform.domain.entity.DevSpec;
import com.aiworkplatform.domain.entity.SpecProjectLink;
import com.aiworkplatform.service.spec.SpecService;
import com.aiworkplatform.web.dto.CreateSpecRequest;
import com.aiworkplatform.web.dto.LinkSpecRequest;
import com.aiworkplatform.web.dto.SpecVO;
import com.aiworkplatform.web.dto.UpdateSpecRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/specs")
public class SpecController {

    private final SpecService specService;

    public SpecController(SpecService specService) {
        this.specService = specService;
    }

    @GetMapping
    public Result<List<SpecVO>> listSpecs() {
        List<SpecVO> vos = specService.listSpecs().stream()
                .map(SpecVO::from)
                .toList();
        return Result.success(vos);
    }

    @PostMapping
    public Result<SpecVO> createSpec(@RequestBody @Valid CreateSpecRequest request) {
        // TODO: 从认证上下文获取 createdBy，暂时硬编码
        DevSpec spec = specService.createSpec(
                request.getName(), request.getSpecType(), request.getContent(), "default");
        return Result.success(SpecVO.from(spec));
    }

    @PutMapping("/{specId}")
    public Result<Void> updateSpec(
            @PathVariable String specId,
            @RequestBody @Valid UpdateSpecRequest request) {
        specService.updateSpec(specId, request.getName(), request.getSpecType(), request.getContent());
        return Result.success(null);
    }

    @DeleteMapping("/{specId}")
    public Result<Void> deleteSpec(@PathVariable String specId) {
        specService.deleteSpec(specId);
        return Result.success(null);
    }

    @GetMapping("/{specId}/links")
    public Result<List<SpecProjectLink>> listLinks(@PathVariable String specId) {
        return Result.success(specService.listLinkedProjects(specId));
    }

    @PostMapping("/{specId}/link")
    public Result<Void> linkToProject(
            @PathVariable String specId,
            @RequestBody @Valid LinkSpecRequest request) {
        specService.linkToProject(specId, request.getProjectId());
        return Result.success(null);
    }

    @DeleteMapping("/{specId}/link/{projectId}")
    public Result<Void> unlinkFromProject(
            @PathVariable String specId,
            @PathVariable String projectId) {
        specService.unlinkFromProject(specId, projectId);
        return Result.success(null);
    }
}
