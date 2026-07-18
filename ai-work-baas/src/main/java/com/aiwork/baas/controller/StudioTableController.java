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

import com.aiwork.baas.controller.dto.TableCreateDTO;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.service.ProjectAccessService;
import com.aiwork.baas.service.TableManagementService;
import com.aiwork.common.core.util.R;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

}
