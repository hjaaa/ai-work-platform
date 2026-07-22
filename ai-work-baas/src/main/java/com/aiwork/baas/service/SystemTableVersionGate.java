package com.aiwork.baas.service;

import com.aiwork.baas.data.error.DataApiException;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.enums.ProjectStatus;
import com.aiwork.baas.exception.DdlConflictException;
import com.aiwork.baas.provision.SystemTableManifest;
import org.springframework.stereotype.Component;

/**
 * 系统表版本准入(spec §9.1):依赖 v3 新列的端点执行任何项目库 SQL 前调用,
 * 未迁移项目 fail-closed,不得以缺列 500 暴露。
 *
 * @author ai-work
 * @date 2026/07/22
 */
@Component
public class SystemTableVersionGate {

    public void assertAuthReady(BaasProject project) {
        if (!isReady(project)) {
            throw DataApiException.forbidden("系统表升级未完成", "项目系统表尚未迁移到当前版本,请稍后重试");
        }
    }

    public void assertStudioReady(BaasProject project) {
        if (!isReady(project)) {
            throw new DdlConflictException("系统表升级未完成,项目当前不允许终端用户管理操作");
        }
    }

    private static boolean isReady(BaasProject project) {
        return project != null && project.getStatus() == ProjectStatus.ACTIVE
                && Integer.valueOf(SystemTableManifest.CURRENT_VERSION).equals(project.getSystemTableVersion());
    }

}
