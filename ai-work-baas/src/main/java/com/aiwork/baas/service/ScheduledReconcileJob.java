/*
 *
 *      Copyright (c) 2018-2025, lengleng All rights reserved.
 *
 */

package com.aiwork.baas.service;

import com.aiwork.baas.ddl.RequestFingerprint;
import com.aiwork.baas.entity.BaasDdlLog;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.enums.DdlLogStatus;
import com.aiwork.baas.entity.enums.DdlOperationType;
import com.aiwork.baas.entity.enums.ProjectStatus;
import com.aiwork.baas.mapper.BaasDdlLogMapper;
import com.aiwork.baas.mapper.BaasProjectMapper;
import com.aiwork.baas.provision.PhysicalPreconditions;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

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

    private final BaasDdlLogMapper ddlLogMapper;

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
            .orderByAsc(BaasProject::getId));
        for (BaasProject project : projects) {
            try {
                reconcileProject(project);
            }
            catch (Exception exception) {
                log.warn("scheduled reconcile skipped projectId={} errorType={}", project.getId(),
                        exception.getClass().getSimpleName());
            }
        }
    }

    private void reconcileProject(BaasProject project) {
        BaasDdlLog leftover = ddlLogMapper.selectOne(Wrappers.<BaasDdlLog>lambdaQuery()
            .eq(BaasDdlLog::getProjectId, project.getId())
            .eq(BaasDdlLog::getOperationType, DdlOperationType.RECONCILE.code())
            .eq(BaasDdlLog::getTriggerSource, ReconcileService.TRIGGER_SCHEDULED)
            .in(BaasDdlLog::getStatus, DdlLogStatus.RUNNING.name(), DdlLogStatus.FAILED.name())
            .last("ORDER BY update_time ASC, id ASC LIMIT 1"));
        if (leftover != null) {
            reconcileService.reconcile(project, leftover.getOperationId(), ReconcileService.TRIGGER_SCHEDULED,
                    leftover.getRequestHash());
            return;
        }
        String operationId = UUID.randomUUID().toString();
        reconcileService.reconcile(project, operationId, ReconcileService.TRIGGER_SCHEDULED,
                RequestFingerprint.scheduledReconcile(project.getId(), operationId));
    }

}
