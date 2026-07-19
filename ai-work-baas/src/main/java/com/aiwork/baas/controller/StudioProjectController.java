/*
 *
 *      Copyright (c) 2018-2025, lengleng All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 * Neither the name of the pig4cloud.com developer nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 * Author: lengleng (wangiegie@gmail.com)
 *
 */

package com.aiwork.baas.controller;

import com.aiwork.baas.controller.dto.ApiKeyVO;
import com.aiwork.baas.controller.dto.CreatedKeyVO;
import com.aiwork.baas.controller.dto.CreatedProjectVO;
import com.aiwork.baas.controller.dto.KeyCreateDTO;
import com.aiwork.baas.controller.dto.ProjectDTO;
import com.aiwork.baas.controller.dto.ProjectPatchDTO;
import com.aiwork.baas.controller.dto.ProjectVO;
import com.aiwork.baas.controller.dto.ReconcileTriggerDTO;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.exception.ProjectNotFoundException;
import com.aiwork.baas.security.CurrentUserProvider;
import com.aiwork.baas.service.ProjectAccessService;
import com.aiwork.baas.service.ProjectKeyService;
import com.aiwork.baas.service.ProjectLifecycleService;
import com.aiwork.baas.service.ReconcileService;
import com.aiwork.baas.service.SystemTableMigrationResult;
import com.aiwork.baas.service.SystemTableMigrationService;
import com.aiwork.common.core.util.R;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Studio 项目管理接口，所有带项目 ref 的操作先执行归属校验。
 *
 * @author ai-work
 * @date 2026/07/17
 */
@RestController
@RequestMapping("/studio/projects")
@RequiredArgsConstructor
public class StudioProjectController {

    private final ProjectLifecycleService lifecycleService;

    private final ProjectAccessService accessService;

    private final ProjectKeyService keyService;

    private final CurrentUserProvider userProvider;

    private final ReconcileService reconcileService;

    private final SystemTableMigrationService migrationService;

    @GetMapping
    public R<List<ProjectVO>> list() {
        return R.ok(accessService.listVisible().stream().map(ProjectVO::from).toList());
    }

    @PostMapping
    public R<CreatedProjectVO> create(@Valid @RequestBody ProjectDTO projectDTO) {
        return R.ok(CreatedProjectVO.from(
                lifecycleService.createProject(projectDTO.name(), userProvider.currentUserId())));
    }

    @GetMapping("/{ref}")
    public R<ProjectVO> detail(@PathVariable("ref") String projectRef) {
        return R.ok(ProjectVO.from(accessService.requireOwned(projectRef)));
    }

    @PatchMapping("/{ref}")
    public R<Void> patch(@PathVariable("ref") String projectRef,
            @Valid @RequestBody ProjectPatchDTO projectPatchDTO) {
        BaasProject project = accessService.requireOwned(projectRef);
        keyService.updateAllowedOrigins(project.getId(), projectPatchDTO.allowedOrigins());
        return R.ok();
    }

    @DeleteMapping("/{ref}")
    public R<Void> delete(@PathVariable("ref") String projectRef) {
        BaasProject project = accessService.requireOwned(projectRef);
        lifecycleService.deleteProject(project.getId(), userProvider.currentUserId());
        return R.ok();
    }

    @PostMapping("/{ref}/reconcile")
    public R<ObjectNode> reconcile(@PathVariable("ref") String projectRef,
            @Valid @RequestBody ReconcileTriggerDTO triggerDTO) {
        BaasProject project = accessService.requireOwned(projectRef);
        return R.ok(reconcileService.manualReconcile(project, triggerDTO));
    }

    @PostMapping("/{ref}/system-tables/migrate")
    public R<SystemTableMigrationResult> migrateSystemTables(@PathVariable("ref") String projectRef) {
        BaasProject project = accessService.requireOwned(projectRef);
        if (!userProvider.isBaasAdmin()) {
            throw new ProjectNotFoundException();
        }
        return R.ok(migrationService.migrate(project));
    }

    @GetMapping("/{ref}/keys")
    public R<List<ApiKeyVO>> listKeys(@PathVariable("ref") String projectRef) {
        BaasProject project = accessService.requireOwned(projectRef);
        return R.ok(keyService.listKeys(project.getId()));
    }

    @PostMapping("/{ref}/keys")
    public R<CreatedKeyVO> createKey(@PathVariable("ref") String projectRef,
            @Valid @RequestBody KeyCreateDTO keyCreateDTO) {
        BaasProject project = accessService.requireOwned(projectRef);
        return R.ok(keyService.createKey(project.getId(), keyCreateDTO.keyType(), userProvider.currentUserId()));
    }

    @PostMapping("/{ref}/keys/{keyId}/revoke")
    public R<Void> revokeKey(@PathVariable("ref") String projectRef, @PathVariable Long keyId) {
        BaasProject project = accessService.requireOwned(projectRef);
        keyService.revokeKey(project.getId(), keyId, userProvider.currentUserId());
        return R.ok();
    }

}
