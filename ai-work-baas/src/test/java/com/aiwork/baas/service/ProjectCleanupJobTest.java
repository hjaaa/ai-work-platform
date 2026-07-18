/*
 *
 *      Copyright (c) 2018-2025, lengleng All rights reserved.
 *
 */

package com.aiwork.baas.service;

import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.mapper.BaasProjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 项目物理清理任务结果与日志脱敏测试。
 *
 * @author ai-work
 * @date 2026/07/19
 */
@ExtendWith(OutputCaptureExtension.class)
class ProjectCleanupJobTest {

    @Test
    void logsOnlyActualCleanupAndNeverLogsThrowableMessage(CapturedOutput output) {
        BaasProjectMapper projectMapper = Mockito.mock(BaasProjectMapper.class);
        ProjectLifecycleService lifecycleService = Mockito.mock(ProjectLifecycleService.class);
        ProjectCleanupJob job = new ProjectCleanupJob(projectMapper, lifecycleService);
        BaasProject cleaned = project(1L, "cleaned-ref");
        BaasProject skipped = project(2L, "skipped-ref");
        BaasProject failed = project(3L, "failed-ref");
        Mockito.when(projectMapper.selectList(Mockito.any())).thenReturn(List.of(cleaned, skipped, failed));
        Mockito.when(lifecycleService.physicallyCleanup(cleaned)).thenReturn(ProjectCleanupResult.CLEANED);
        Mockito.when(lifecycleService.physicallyCleanup(skipped)).thenReturn(ProjectCleanupResult.SKIPPED);
        Mockito.when(lifecycleService.physicallyCleanup(failed))
            .thenThrow(new IllegalStateException("secret-runtime-password"));

        job.cleanup();

        String logs = output.getOut();
        assertThat(logs).contains("baas project cleaned-ref physically cleaned")
            .doesNotContain("baas project skipped-ref physically cleaned", "secret-runtime-password");
        String failureLine = logs.lines()
            .filter(line -> line.contains("cleanup project failed-ref failed"))
            .findFirst()
            .orElseThrow();
        assertThat(failureLine).contains("errorType=IllegalStateException")
            .doesNotContain("secret-runtime-password");
    }

    private BaasProject project(Long id, String projectRef) {
        BaasProject project = new BaasProject();
        project.setId(id);
        project.setProjectRef(projectRef);
        return project;
    }

}
