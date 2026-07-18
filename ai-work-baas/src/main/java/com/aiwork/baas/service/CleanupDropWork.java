/*
 *
 *      Copyright (c) 2018-2025, lengleng All rights reserved.
 *
 */

package com.aiwork.baas.service;

import com.aiwork.baas.ddl.engine.DdlWork;
import com.aiwork.baas.ddl.engine.DdlWorkContext;
import com.aiwork.baas.ddl.engine.OwnershipBranch;
import com.aiwork.baas.ddl.render.DdlRenderer;
import com.aiwork.baas.entity.BaasColumn;
import com.aiwork.baas.entity.BaasDdlLog;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.BaasTable;
import com.aiwork.baas.entity.BaasTableAcl;
import com.aiwork.baas.entity.enums.DdlStep;
import com.aiwork.baas.entity.enums.ProjectStatus;
import com.aiwork.baas.entity.enums.TableStatus;
import com.aiwork.baas.exception.DdlConflictException;
import com.aiwork.baas.mapper.BaasColumnMapper;
import com.aiwork.baas.mapper.BaasProjectMapper;
import com.aiwork.baas.mapper.BaasTableAclMapper;
import com.aiwork.baas.mapper.BaasTableMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 到期物理删表工作：只按 cleanup 日志的不可变 table_id 重读目标，避免误删同名重建表。
 *
 * @author ai-work
 * @date 2026/07/18
 */
final class CleanupDropWork implements DdlWork {

    private final BaasProject project;

    private final BaasDdlLog cleanupRecord;

    private final BaasProjectMapper projectMapper;

    private final BaasTableMapper tableMapper;

    private final BaasColumnMapper columnMapper;

    private final BaasTableAclMapper aclMapper;

    private final ObjectMapper objectMapper;

    private BaasTable target;

    private boolean noop;

    CleanupDropWork(BaasProject project, BaasDdlLog cleanupRecord, BaasProjectMapper projectMapper,
            BaasTableMapper tableMapper, BaasColumnMapper columnMapper, BaasTableAclMapper aclMapper,
            ObjectMapper objectMapper) {
        this.project = project;
        this.cleanupRecord = cleanupRecord;
        this.projectMapper = projectMapper;
        this.tableMapper = tableMapper;
        this.columnMapper = columnMapper;
        this.aclMapper = aclMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void validateInLock(DdlWorkContext context) {
        if (context.branch() == OwnershipBranch.NEW_OPERATION) {
            throw new DdlConflictException("cleanup-drop 只处理预建记录");
        }
        BaasProject currentProject = projectMapper.selectById(project.getId());
        if (currentProject == null || currentProject.getStatus() != ProjectStatus.ACTIVE) {
            throw new DdlConflictException("项目当前状态不允许表清理");
        }

        target = tableMapper.selectById(cleanupRecord.getTableId());
        if (!matchesImmutableTarget(target)) {
            noop = true;
            return;
        }

        if (target.getDeleteAfter() == null || target.getDeleteAfter().isAfter(LocalDateTime.now())) {
            throw new DdlConflictException("cleanup 未到期，保持 PENDING");
        }
    }

    @Override
    public ObjectNode perform(DdlWorkContext context) {
        if (noop) {
            return context.completeSuccess(() -> objectMapper.createObjectNode().put("noop", true));
        }

        if (!context.stepReached(DdlStep.DDL_APPLIED)) {
            DdlRenderer.RenderedDdl rendered = DdlRenderer.renderDropTable(project.getDbName(),
                    target.getTableName());
            context.projectJdbc().execute(rendered.sql());
            context.advanceToDdlApplied();
        }

        return context.completeSuccess(() -> {
            columnMapper.delete(Wrappers.<BaasColumn>lambdaQuery().eq(BaasColumn::getTableId, target.getId()));
            aclMapper.delete(Wrappers.<BaasTableAcl>lambdaQuery().eq(BaasTableAcl::getTableId, target.getId()));
            if (tableMapper.deleteById(target.getId()) != 1) {
                throw new DdlConflictException("cleanup 元数据删除竞争失败");
            }
            return objectMapper.createObjectNode().put("dropped", target.getTableName());
        });
    }

    private boolean matchesImmutableTarget(BaasTable table) {
        return table != null && Objects.equals(table.getProjectId(), project.getId())
                && Objects.equals(table.getTableName(), cleanupRecord.getTableName())
                && TableStatus.DELETED.name().equals(table.getStatus());
    }

}
