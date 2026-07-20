/*
 *
 *      Copyright (c) 2018-2026, lengleng All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice,
 *  this list of conditions and the following disclaimer.
 *  Redistributions in binary form must reproduce the above copyright
 *  notice, this list of conditions and the following disclaimer in the
 *  documentation and/or other materials provided with the distribution.
 *  Neither the name of the pig4cloud.com developer nor the names of its
 *  contributors may be used to endorse or promote products derived from
 *  this software without specific prior written permission.
 *  Author: lengleng (wangiegie@gmail.com)
 *
 */

package com.aiwork.baas.service;

import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.enums.ProjectStatus;
import com.aiwork.baas.mapper.BaasProjectMapper;
import com.aiwork.baas.provision.PhysicalPreconditions;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 定时对账(spec §9.4,默认关闭):优先复用遗留 SCHEDULED FAILED 或陈旧 RUNNING 记录。
 *
 * @author ai-work
 * @date 2026/07/19
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledReconcileJob {

    private final PhysicalPreconditions physicalPreconditions;

    private final ReconcileService reconcileService;

    private final BaasProjectMapper projectMapper;

    @Value("${baas.reconcile.scheduled-enabled:false}")
    private boolean enabled;

    @Scheduled(initialDelayString = "${baas.reconcile.scheduled-interval-millis:3600000}",
            fixedDelayString = "${baas.reconcile.scheduled-interval-millis:3600000}")
    public void scheduledScan() {
        if (enabled) {
            scanOnce();
        }
    }

    /** 执行一轮定时对账扫描;物理前置条件是任何扫描、锁或副作用前的第一动作。 */
    public void scanOnce() {
        physicalPreconditions.assertSatisfied();
        List<BaasProject> projects = projectMapper.selectList(Wrappers.<BaasProject>lambdaQuery()
            .eq(BaasProject::getStatus, ProjectStatus.ACTIVE)
            .orderByAsc(BaasProject::getId).last("LIMIT 100"));
        for (BaasProject project : projects) {
            try {
                reconcileService.scheduledReconcile(project);
            }
            catch (Exception exception) {
                log.warn("scheduled reconcile skipped projectId={} errorType={}", project.getId(),
                        exception.getClass().getSimpleName());
            }
        }
    }

}
