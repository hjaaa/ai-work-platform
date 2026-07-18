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
import com.aiwork.baas.mapper.BaasProjectMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 延迟物理清理：对处于 DELETING 且已过延迟期的项目执行 DROP(spec §9.3)。
 *
 * @author ai-work
 * @date 2026/07/17
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectCleanupJob {

    private final BaasProjectMapper projectMapper;

    private final ProjectLifecycleService lifecycleService;

    @Scheduled(fixedDelay = 3600000)
    public void cleanup() {
        List<BaasProject> expiredProjects = projectMapper.selectList(Wrappers.<BaasProject>lambdaQuery()
            .eq(BaasProject::getStatus, ProjectStatus.DELETING)
            .lt(BaasProject::getDeleteAfter, LocalDateTime.now()));
        for (BaasProject project : expiredProjects) {
            try {
                ProjectCleanupResult result = lifecycleService.physicallyCleanup(project);
                if (result == ProjectCleanupResult.CLEANED && log.isInfoEnabled()) {
                    log.info("baas project {} physically cleaned", project.getProjectRef());
                }
            }
            catch (Exception exception) {
                log.error("cleanup project {} failed errorType={}", project.getProjectRef(),
                        exception.getClass().getSimpleName());
            }
        }
    }

}
