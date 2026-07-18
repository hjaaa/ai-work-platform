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

package com.aiwork.baas.service;

import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.enums.ProjectStatus;
import com.aiwork.baas.exception.ProjectNotFoundException;
import com.aiwork.baas.mapper.BaasProjectMapper;
import com.aiwork.baas.security.CurrentUserProvider;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Studio 项目归属校验，防止通过项目 ref 越权访问。
 *
 * @author ai-work
 * @date 2026/07/17
 */
@Service
@RequiredArgsConstructor
public class ProjectAccessService {

    private final BaasProjectMapper projectMapper;

    private final CurrentUserProvider userProvider;

    public BaasProject requireOwned(String projectRef) {
        BaasProject project = projectMapper.selectOne(Wrappers.<BaasProject>lambdaQuery()
            .eq(BaasProject::getProjectRef, projectRef)
            .ne(BaasProject::getStatus, ProjectStatus.DELETED));
        if (project == null) {
            throw new ProjectNotFoundException();
        }

        boolean ownedByCurrentUser = Objects.equals(project.getOwnerUserId(), userProvider.currentUserId());
        if (!ownedByCurrentUser && !userProvider.isBaasAdmin()) {
            throw new ProjectNotFoundException();
        }

        return project;
    }

    public List<BaasProject> listVisible() {
        return projectMapper.selectList(Wrappers.<BaasProject>lambdaQuery()
            .ne(BaasProject::getStatus, ProjectStatus.DELETED)
            .eq(BaasProject::getOwnerUserId, userProvider.currentUserId()));
    }

}
