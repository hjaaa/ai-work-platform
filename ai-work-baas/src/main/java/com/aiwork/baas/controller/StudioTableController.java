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

import com.aiwork.baas.controller.dto.AclPutDTO;
import com.aiwork.baas.controller.dto.TableAlterDTO;
import com.aiwork.baas.controller.dto.TableCreateDTO;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.service.AclConfigService;
import com.aiwork.baas.service.ProjectAccessService;
import com.aiwork.baas.service.TableManagementService;
import com.aiwork.common.core.util.R;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Studio 表管理接口(spec §7.3)，归属校验先行。
 *
 * @author ai-work
 * @date 2026/07/18
 */
@RestController
@RequestMapping("/studio/projects/{ref}/tables")
@RequiredArgsConstructor
public class StudioTableController {

    private final ProjectAccessService accessService;

    private final TableManagementService tableService;

    private final AclConfigService aclService;

    @GetMapping
    public R<ArrayNode> list(@PathVariable("ref") String projectRef) {
        BaasProject project = accessService.requireOwned(projectRef);
        return R.ok(tableService.listTables(project));
    }

    @PostMapping
    public R<ObjectNode> create(@PathVariable("ref") String projectRef,
            @Valid @RequestBody TableCreateDTO createDTO) {
        BaasProject project = accessService.requireOwned(projectRef);
        return R.ok(tableService.createTable(project, createDTO));
    }

    @GetMapping("/{table}")
    public R<ObjectNode> detail(@PathVariable("ref") String projectRef,
            @PathVariable("table") String tableName) {
        BaasProject project = accessService.requireOwned(projectRef);
        return R.ok(tableService.getTableSnapshot(project, tableName));
    }

    @PatchMapping("/{table}")
    public R<ObjectNode> alter(@PathVariable("ref") String projectRef, @PathVariable("table") String tableName,
            @Valid @RequestBody TableAlterDTO alterDTO) {
        BaasProject project = accessService.requireOwned(projectRef);
        return R.ok(tableService.alterTable(project, tableName, alterDTO));
    }

    @DeleteMapping("/{table}")
    public R<ObjectNode> drop(@PathVariable("ref") String projectRef, @PathVariable("table") String tableName,
            @RequestParam("operationId") String operationId) {
        BaasProject project = accessService.requireOwned(projectRef);
        return R.ok(tableService.dropTable(project, tableName, operationId));
    }

    @GetMapping("/{table}/acl")
    public R<ObjectNode> getAcl(@PathVariable("ref") String projectRef,
            @PathVariable("table") String tableName) {
        BaasProject project = accessService.requireOwned(projectRef);
        return R.ok(aclService.getAcl(project, tableName));
    }

    @PutMapping("/{table}/acl")
    public R<ObjectNode> putAcl(@PathVariable("ref") String projectRef, @PathVariable("table") String tableName,
            @Valid @RequestBody AclPutDTO aclPutDTO) {
        BaasProject project = accessService.requireOwned(projectRef);
        return R.ok(aclService.putAcl(project, tableName, aclPutDTO));
    }

}
