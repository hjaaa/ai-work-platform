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

import com.aiwork.baas.controller.dto.ColumnDefinitionDTO;
import com.aiwork.baas.controller.dto.TableCreateDTO;
import com.aiwork.baas.ddl.OperationIdValidator;
import com.aiwork.baas.ddl.RequestFingerprint;
import com.aiwork.baas.ddl.engine.DdlExecutionEngine;
import com.aiwork.baas.ddl.engine.DdlOperationSpec;
import com.aiwork.baas.ddl.engine.DdlWork;
import com.aiwork.baas.ddl.engine.DdlWorkContext;
import com.aiwork.baas.ddl.engine.OwnershipBranch;
import com.aiwork.baas.ddl.index.IndexAdmission;
import com.aiwork.baas.ddl.inspect.DdlTargetMatcher;
import com.aiwork.baas.ddl.inspect.PhysicalTable;
import com.aiwork.baas.ddl.inspect.SchemaInspector;
import com.aiwork.baas.ddl.render.DdlRenderer;
import com.aiwork.baas.ddl.type.ColumnType;
import com.aiwork.baas.ddl.type.ColumnTypeValidator;
import com.aiwork.baas.ddl.type.DefaultValueRenderer;
import com.aiwork.baas.ddl.type.LogicalColumn;
import com.aiwork.baas.entity.BaasAuditLog;
import com.aiwork.baas.entity.BaasColumn;
import com.aiwork.baas.entity.BaasDdlLog;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.BaasTable;
import com.aiwork.baas.entity.BaasTableAcl;
import com.aiwork.baas.entity.enums.DdlOperationType;
import com.aiwork.baas.entity.enums.DdlStep;
import com.aiwork.baas.entity.enums.ProjectStatus;
import com.aiwork.baas.entity.enums.TableStatus;
import com.aiwork.baas.exception.BaasBadRequestException;
import com.aiwork.baas.exception.DdlConflictException;
import com.aiwork.baas.exception.TableNotFoundException;
import com.aiwork.baas.mapper.BaasAuditLogMapper;
import com.aiwork.baas.mapper.BaasColumnMapper;
import com.aiwork.baas.mapper.BaasDdlLogMapper;
import com.aiwork.baas.mapper.BaasProjectMapper;
import com.aiwork.baas.mapper.BaasTableAclMapper;
import com.aiwork.baas.mapper.BaasTableMapper;
import com.aiwork.baas.provision.IdentifierValidator;
import com.aiwork.baas.security.CurrentUserProvider;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 表管理(spec §7.3/§9.5)：全部写路径经 DdlExecutionEngine 进入。
 *
 * @author ai-work
 * @date 2026/07/18
 */
@Service
@RequiredArgsConstructor
public class TableManagementService {

    private final DdlExecutionEngine engine;

    private final BaasProjectMapper projectMapper;

    private final BaasTableMapper tableMapper;

    private final BaasColumnMapper columnMapper;

    private final BaasTableAclMapper aclMapper;

    private final BaasDdlLogMapper ddlLogMapper;

    private final BaasAuditLogMapper auditLogMapper;

    private final CurrentUserProvider userProvider;

    private final ObjectMapper objectMapper;

    /** 所有权事务内补写日志行的目标表 ID 与脱敏 DDL(spec §6.1；两参均可为 null 表示不更新)。 */
    void patchDdlLog(Long logId, Long tableId, String sanitizedSql) {
        BaasDdlLog patch = new BaasDdlLog();
        patch.setId(logId);
        patch.setTableId(tableId);
        patch.setDdlText(sanitizedSql);
        ddlLogMapper.updateById(patch);
    }

    /** DDL 操作审计(spec §12.2：DDL 操作入 baas_audit_log；在守卫元数据事务内调用)。 */
    void auditDdl(Long projectId, String action, String detail, String level) {
        BaasAuditLog auditLog = new BaasAuditLog();
        auditLog.setProjectId(projectId);
        auditLog.setOperatorUserId(userProvider.currentUserId());
        auditLog.setAction(action);
        auditLog.setDetail(detail);
        auditLog.setLevel(level);
        auditLogMapper.insert(auditLog);
    }

    /** DTO 转逻辑列：参数矩阵、默认值渲染与索引准入静态校验。 */
    public static DdlRenderer.ColumnPlan toColumnPlan(ColumnDefinitionDTO dto) {
        if (dto == null) {
            throw new BaasBadRequestException("列定义不能为空");
        }
        requireIdentifier(dto.columnName(), "列名不合法");
        if ("id".equals(dto.columnName())) {
            throw new BaasBadRequestException("id 为服务端自动生成的主键，列定义中不得出现");
        }
        ColumnType type = ColumnTypeValidator.validateTypeParams(dto.dataType(), dto.length(), dto.scale());
        Integer scale = ColumnTypeValidator.normalizeScale(type, dto.scale());
        boolean unique = dto.uniqueOrDefault();
        boolean indexed = !unique && dto.indexedOrDefault();
        IndexAdmission.validateColumnIndexRequest(type, dto.length(), unique, indexed);
        DefaultValueRenderer.Rendered rendered = DefaultValueRenderer.render(type, dto.length(), scale,
                dto.defaultValue());
        LogicalColumn column = new LogicalColumn(dto.columnName(), type, dto.length(), scale,
                dto.nullableOrDefault(), rendered == null ? null : rendered.canonical(), false, false, unique,
                indexed, dto.comment());
        return DdlRenderer.columnPlan(column, dto.defaultValue());
    }

    /** 逻辑列转元数据实体。 */
    public static BaasColumn columnEntityFrom(Long tableId, LogicalColumn column) {
        BaasColumn entity = new BaasColumn();
        entity.setTableId(tableId);
        entity.setColumnName(column.columnName());
        entity.setDataType(column.type().code());
        entity.setLength(column.length());
        entity.setScale(column.scale());
        entity.setNullable(column.nullable());
        entity.setDefaultValue(column.defaultValue());
        entity.setPk(column.pk());
        entity.setAutoIncrement(column.autoIncrement());
        entity.setUnique(column.unique());
        entity.setIndexed(column.indexed());
        entity.setComment(column.comment());
        return entity;
    }

    static LogicalColumn idColumn() {
        return new LogicalColumn("id", ColumnType.BIGINT, null, null, false, null, true, true, false, false, null);
    }

    BaasTable findTableRow(Long projectId, String tableName) {
        return tableMapper.selectOne(Wrappers.<BaasTable>lambdaQuery()
            .eq(BaasTable::getProjectId, projectId)
            .eq(BaasTable::getTableName, tableName));
    }

    void requireProjectActiveInLock(Long projectId) {
        BaasProject current = projectMapper.selectById(projectId);
        if (current == null || current.getStatus() != ProjectStatus.ACTIVE) {
            throw new DdlConflictException("项目非 ACTIVE，禁止 Schema 操作");
        }
    }

    ObjectNode snapshotOf(BaasTable table) {
        List<BaasColumn> columns = columnMapper.selectList(Wrappers.<BaasColumn>lambdaQuery()
            .eq(BaasColumn::getTableId, table.getId())
            .orderByAsc(BaasColumn::getId));
        List<BaasTableAcl> acls = aclMapper
            .selectList(Wrappers.<BaasTableAcl>lambdaQuery().eq(BaasTableAcl::getTableId, table.getId()));
        return TableSnapshotBuilder.build(objectMapper, table, columns, acls);
    }

    public ObjectNode createTable(BaasProject project, TableCreateDTO dto) {
        if (project == null || dto == null || dto.columns() == null || dto.columns().isEmpty()) {
            throw new BaasBadRequestException("建表请求不完整");
        }
        OperationIdValidator.requireUuid(dto.operationId());
        requireIdentifier(dto.tableName(), "表名不合法");
        Set<String> seen = new HashSet<>();
        List<DdlRenderer.ColumnPlan> plans = new ArrayList<>();
        for (ColumnDefinitionDTO columnDto : dto.columns()) {
            if (columnDto == null) {
                throw new BaasBadRequestException("列定义不能为空");
            }
            if (!seen.add(columnDto.columnName())) {
                throw new BaasBadRequestException("列名重复: " + columnDto.columnName());
            }
            plans.add(toColumnPlan(columnDto));
        }
        IndexAdmission.validateFinalStructure(plans.stream().map(DdlRenderer.ColumnPlan::column).toList(),
                (int) plans.stream().filter(plan -> plan.column().unique() || plan.column().indexed()).count());

        DdlRenderer.RenderedDdl rendered = DdlRenderer.renderCreateTable(project.getDbName(), dto.tableName(),
                dto.comment(), plans);
        String path = "/studio/projects/" + project.getProjectRef() + "/tables";
        String hash = RequestFingerprint.http("POST", path, DdlOperationType.CREATE.code(),
                RequestFingerprint.canonicalBody(dto));
        DdlOperationSpec spec = new DdlOperationSpec(project.getId(), dto.operationId(), DdlOperationType.CREATE,
                dto.tableName(), null, hash, null, rendered.sanitizedSql());

        return engine.execute(spec, new CreateTableWork(project, dto, plans, rendered));
    }

    private final class CreateTableWork implements DdlWork {

        private final BaasProject project;

        private final TableCreateDTO dto;

        private final List<DdlRenderer.ColumnPlan> plans;

        private final DdlRenderer.RenderedDdl rendered;

        private BaasTable tableRow;

        private boolean ddlAlreadyApplied;

        private CreateTableWork(BaasProject project, TableCreateDTO dto, List<DdlRenderer.ColumnPlan> plans,
                DdlRenderer.RenderedDdl rendered) {
            this.project = project;
            this.dto = dto;
            this.plans = plans;
            this.rendered = rendered;
        }

        @Override
        public void validateInLock(DdlWorkContext context) {
            requireProjectActiveInLock(project.getId());
            BaasTable existing = findTableRow(project.getId(), dto.tableName());
            PhysicalTable physical = SchemaInspector.readTable(context.projectJdbc(), project.getDbName(),
                    dto.tableName());
            if (context.branch() == OwnershipBranch.NEW_OPERATION) {
                if (existing != null) {
                    throw new DdlConflictException("表名已存在或处于删除保护期: " + dto.tableName());
                }
                if (physical != null) {
                    throw new DdlConflictException("项目库已存在同名外部表，请通过 reconcile 准入导入");
                }
                return;
            }
            if (context.branch() == OwnershipBranch.CLAIM_PENDING) {
                throw new DdlConflictException("建表操作不存在 PENDING 分支");
            }
            if (existing == null || (!TableStatus.CREATING.name().equals(existing.getStatus())
                    && !TableStatus.FAILED.name().equals(existing.getStatus()))) {
                throw new DdlConflictException("建表重试要求表处于 CREATING/FAILED 状态");
            }
            tableRow = existing;
            if (physical != null) {
                List<LogicalColumn> expectedColumns = new ArrayList<>();
                expectedColumns.add(idColumn());
                expectedColumns.addAll(plans.stream().map(DdlRenderer.ColumnPlan::column).toList());
                if (!DdlTargetMatcher.matches(physical, dto.tableName(), dto.comment(), expectedColumns)) {
                    throw new DdlConflictException("同名物理表与本次 CREATE 目标不一致，拒绝错误续跑");
                }
                ddlAlreadyApplied = true;
            }
        }

        @Override
        public void inOwnershipTx(DdlWorkContext context) {
            if (context.branch() == OwnershipBranch.NEW_OPERATION) {
                BaasTable row = new BaasTable();
                row.setProjectId(project.getId());
                row.setTableName(dto.tableName());
                row.setComment(dto.comment());
                row.setStatus(TableStatus.CREATING.name());
                tableMapper.insert(row);
                tableRow = row;
                patchDdlLog(context.logId(), row.getId(), null);
            }
            else {
                int updated = tableMapper.update(null, Wrappers.<BaasTable>lambdaUpdate()
                    .eq(BaasTable::getId, tableRow.getId())
                    .in(BaasTable::getStatus, TableStatus.CREATING.name(), TableStatus.FAILED.name())
                    .set(BaasTable::getStatus, TableStatus.CREATING.name()));
                if (updated != 1) {
                    throw new DdlConflictException("建表重试状态竞争失败");
                }
            }
        }

        @Override
        public ObjectNode perform(DdlWorkContext context) {
            if (!context.stepReached(DdlStep.DDL_APPLIED)) {
                if (!ddlAlreadyApplied) {
                    context.projectJdbc().execute(rendered.sql());
                }
                context.advanceToDdlApplied();
            }
            return context.completeSuccess(() -> {
                columnMapper.delete(Wrappers.<BaasColumn>lambdaQuery().eq(BaasColumn::getTableId, tableRow.getId()));
                columnMapper.insert(columnEntityFrom(tableRow.getId(), idColumn()));
                for (DdlRenderer.ColumnPlan plan : plans) {
                    columnMapper.insert(columnEntityFrom(tableRow.getId(), plan.column()));
                }
                for (String role : List.of("anon", "authenticated")) {
                    boolean exists = aclMapper.selectCount(Wrappers.<BaasTableAcl>lambdaQuery()
                        .eq(BaasTableAcl::getTableId, tableRow.getId())
                        .eq(BaasTableAcl::getRole, role)) > 0;
                    if (!exists) {
                        BaasTableAcl acl = new BaasTableAcl();
                        acl.setTableId(tableRow.getId());
                        acl.setRole(role);
                        acl.setCanSelect(false);
                        acl.setCanInsert(false);
                        acl.setCanUpdate(false);
                        acl.setCanDelete(false);
                        aclMapper.insert(acl);
                    }
                }
                int updated = tableMapper.update(null, Wrappers.<BaasTable>lambdaUpdate()
                    .eq(BaasTable::getId, tableRow.getId())
                    .eq(BaasTable::getStatus, TableStatus.CREATING.name())
                    .set(BaasTable::getStatus, TableStatus.ACTIVE.name()));
                if (updated != 1) {
                    throw new DdlConflictException("建表完成状态竞争失败");
                }
                tableRow.setStatus(TableStatus.ACTIVE.name());
                auditDdl(project.getId(), "TABLE_CREATE", "table=" + dto.tableName(), "INFO");
                return snapshotOf(tableRow);
            });
        }

        @Override
        public void onFailureTx(DdlWorkContext context) {
            if (tableRow != null) {
                tableMapper.update(null, Wrappers.<BaasTable>lambdaUpdate()
                    .eq(BaasTable::getId, tableRow.getId())
                    .eq(BaasTable::getStatus, TableStatus.CREATING.name())
                    .set(BaasTable::getStatus, TableStatus.FAILED.name()));
            }
        }

    }

    public ArrayNode listTables(BaasProject project) {
        ArrayNode array = objectMapper.createArrayNode();
        for (BaasTable table : tableMapper.selectList(Wrappers.<BaasTable>lambdaQuery()
            .eq(BaasTable::getProjectId, project.getId())
            .orderByAsc(BaasTable::getTableName))) {
            ObjectNode node = array.addObject();
            node.put("tableName", table.getTableName());
            node.put("status", table.getStatus());
            node.put("comment", table.getComment());
            node.put("ownerColumn", table.getOwnerColumn());
        }
        return array;
    }

    public ObjectNode getTableSnapshot(BaasProject project, String tableName) {
        BaasTable table = findTableRow(project.getId(), tableName);
        if (table == null || TableStatus.DELETED.name().equals(table.getStatus())) {
            throw new TableNotFoundException();
        }
        return snapshotOf(table);
    }

    private static void requireIdentifier(String identifier, String message) {
        try {
            IdentifierValidator.validate(identifier);
        }
        catch (IllegalArgumentException exception) {
            throw new BaasBadRequestException(message);
        }
    }

}
