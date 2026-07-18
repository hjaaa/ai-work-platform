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

import com.aiwork.baas.controller.dto.AclPutDTO;
import com.aiwork.baas.controller.dto.AclRoleDTO;
import com.aiwork.baas.ddl.OperationIdValidator;
import com.aiwork.baas.ddl.RequestFingerprint;
import com.aiwork.baas.ddl.engine.DdlExecutionEngine;
import com.aiwork.baas.ddl.engine.DdlOperationSpec;
import com.aiwork.baas.ddl.engine.DdlWork;
import com.aiwork.baas.ddl.engine.DdlWorkContext;
import com.aiwork.baas.ddl.engine.OwnershipBranch;
import com.aiwork.baas.ddl.index.IndexAdmission;
import com.aiwork.baas.ddl.index.IndexNameAllocator;
import com.aiwork.baas.ddl.inspect.LogicalModelMapper;
import com.aiwork.baas.ddl.inspect.MappingOutcome;
import com.aiwork.baas.ddl.inspect.PhysicalColumn;
import com.aiwork.baas.ddl.inspect.PhysicalIndex;
import com.aiwork.baas.ddl.inspect.PhysicalTable;
import com.aiwork.baas.ddl.inspect.SchemaInspector;
import com.aiwork.baas.ddl.render.DdlRenderer;
import com.aiwork.baas.ddl.type.ColumnType;
import com.aiwork.baas.ddl.type.LogicalColumn;
import com.aiwork.baas.entity.BaasColumn;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.BaasTable;
import com.aiwork.baas.entity.BaasTableAcl;
import com.aiwork.baas.entity.enums.DdlOperationType;
import com.aiwork.baas.entity.enums.DdlStep;
import com.aiwork.baas.entity.enums.TableStatus;
import com.aiwork.baas.exception.BaasBadRequestException;
import com.aiwork.baas.exception.DdlConflictException;
import com.aiwork.baas.exception.TableNotFoundException;
import com.aiwork.baas.mapper.BaasColumnMapper;
import com.aiwork.baas.mapper.BaasTableAclMapper;
import com.aiwork.baas.mapper.BaasTableMapper;
import com.aiwork.baas.provision.IdentifierValidator;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 表级 ACL 与 owner 列配置(spec §8.3):一律经双层 DDL 锁,acl-config 操作入日志。
 *
 * @author ai-work
 * @date 2026/07/18
 */
@Service
@RequiredArgsConstructor
public class AclConfigService {

    private static final List<String> ACL_ROLES = List.of("anon", "authenticated");

    /** MySQL 服务层将 PRIMARY 也计入最多 64 个 key 的限制。 */
    private static final int MYSQL_MAX_TOTAL_INDEXES = 64;

    private static final AclRoleDTO ALL_OFF = new AclRoleDTO(false, false, false, false);

    private final DdlExecutionEngine engine;

    private final TableManagementService tableService;

    private final BaasTableMapper tableMapper;

    private final BaasColumnMapper columnMapper;

    private final BaasTableAclMapper aclMapper;

    private final ObjectMapper objectMapper;

    public ObjectNode getAcl(BaasProject project, String tableName) {
        requireIdentifier(tableName, "表名不合法");
        BaasTable table = tableService.findTableRow(project.getId(), tableName);
        if (table == null || TableStatus.DELETED.name().equals(table.getStatus())) {
            throw new TableNotFoundException();
        }
        return aclSnapshot(table, null);
    }

    public ObjectNode putAcl(BaasProject project, String tableName, AclPutDTO dto) {
        if (project == null || dto == null || dto.acl() == null || dto.acl().anon() == null
                || dto.acl().authenticated() == null) {
            throw new BaasBadRequestException("ACL 配置请求不完整");
        }
        OperationIdValidator.requireUuid(dto.operationId());
        requireIdentifier(tableName, "表名不合法");
        if (dto.ownerColumn() != null) {
            requireIdentifier(dto.ownerColumn(), "ownerColumn 不合法");
            if ("id".equals(dto.ownerColumn())) {
                throw new BaasBadRequestException("ownerColumn 不得是主键列 id(spec §8.3)");
            }
        }

        String path = "/studio/projects/" + project.getProjectRef() + "/tables/" + tableName + "/acl";
        String requestHash = RequestFingerprint.http("PUT", path, DdlOperationType.ACL_CONFIG.code(),
                RequestFingerprint.canonicalBody(dto));
        DdlOperationSpec spec = new DdlOperationSpec(project.getId(), dto.operationId(),
                DdlOperationType.ACL_CONFIG, tableName, null, requestHash, null, null);
        return engine.execute(spec, new AclConfigWork(project, tableName, dto));
    }

    private ObjectNode aclSnapshot(BaasTable table, Boolean closedByCancel) {
        List<BaasTableAcl> aclRows = aclMapper
            .selectList(Wrappers.<BaasTableAcl>lambdaQuery().eq(BaasTableAcl::getTableId, table.getId()));
        ObjectNode root = objectMapper.createObjectNode();
        root.put("tableName", table.getTableName());
        root.put("ownerColumn", table.getOwnerColumn());
        ObjectNode aclNode = root.putObject("acl");
        for (String role : ACL_ROLES) {
            BaasTableAcl acl = aclRows.stream().filter(item -> role.equals(item.getRole())).findFirst().orElse(null);
            ObjectNode roleNode = aclNode.putObject(role);
            roleNode.put("select", acl != null && Boolean.TRUE.equals(acl.getCanSelect()));
            roleNode.put("insert", acl != null && Boolean.TRUE.equals(acl.getCanInsert()));
            roleNode.put("update", acl != null && Boolean.TRUE.equals(acl.getCanUpdate()));
            roleNode.put("delete", acl != null && Boolean.TRUE.equals(acl.getCanDelete()));
        }
        if (closedByCancel != null) {
            root.put("aclClosedByOwnerCancel", closedByCancel);
        }
        return root;
    }

    private static void requireIdentifier(String identifier, String message) {
        try {
            IdentifierValidator.validate(identifier);
        }
        catch (IllegalArgumentException exception) {
            throw new BaasBadRequestException(message);
        }
    }

    private final class AclConfigWork implements DdlWork {

        private final BaasProject project;

        private final String tableName;

        private final AclPutDTO dto;

        private BaasTable tableRow;

        private boolean indexOperation;

        private boolean needIndex;

        private boolean cancelOwner;

        private boolean ownerUnique;

        private boolean ownerIndexed;

        private boolean persistedDdlIntent;

        private DdlRenderer.RenderedDdl renderedDdl;

        private AclConfigWork(BaasProject project, String tableName, AclPutDTO dto) {
            this.project = project;
            this.tableName = tableName;
            this.dto = dto;
        }

        @Override
        public void validateInLock(DdlWorkContext context) {
            tableService.requireProjectActiveInLock(project.getId());
            tableRow = resolveTableRow(context);
            if (tableRow == null) {
                if (context.branch() == OwnershipBranch.NEW_OPERATION) {
                    throw new TableNotFoundException();
                }
                throw new DdlConflictException("acl-config 重试目标已不存在或已变化");
            }
            if (context.branch() == OwnershipBranch.CLAIM_PENDING) {
                throw new DdlConflictException("acl-config 不存在 PENDING 分支");
            }
            persistedDdlIntent = context.branch() != OwnershipBranch.NEW_OPERATION
                    && context.existingLog() != null && context.existingLog().getDdlText() != null
                    && !context.existingLog().getDdlText().isBlank();
            String status = tableRow.getStatus();
            boolean active = TableStatus.ACTIVE.name().equals(status);
            boolean retryableDdlState = persistedDdlIntent
                    && (TableStatus.ALTERING.name().equals(status) || TableStatus.CONFLICT.name().equals(status));
            if (!active && !retryableDdlState) {
                throw new DdlConflictException("表当前状态不允许 ACL 配置: " + status);
            }

            cancelOwner = dto.ownerColumn() == null && tableRow.getOwnerColumn() != null;
            if (dto.ownerColumn() == null) {
                return;
            }
            PhysicalTable physical = readManagedPhysicalTable(context);
            OwnerIndexMapping ownerMapping = inspectPhysicalStructure(physical);
            prepareIndexOperation(physical, ownerMapping.logicalColumn(), ownerMapping.index());
        }

        private BaasTable resolveTableRow(DdlWorkContext context) {
            if (context.branch() == OwnershipBranch.NEW_OPERATION) {
                return tableService.findTableRow(project.getId(), tableName);
            }
            if (context.existingLog() == null || context.existingLog().getTableId() == null) {
                return null;
            }
            BaasTable original = tableMapper.selectById(context.existingLog().getTableId());
            if (original == null || !project.getId().equals(original.getProjectId())
                    || !tableName.equals(original.getTableName())) {
                return null;
            }
            return original;
        }

        private PhysicalTable readManagedPhysicalTable(DdlWorkContext context) {
            PhysicalTable physical = SchemaInspector.readTable(context.projectJdbc(), project.getDbName(), tableName);
            if (physical == null) {
                throw new DdlConflictException("项目库中不存在该表,请先对账处理");
            }
            if (!"BASE TABLE".equals(physical.tableType()) || !"InnoDB".equals(physical.engine())
                    || !"Dynamic".equals(physical.rowFormat())
                    || !"utf8mb4_general_ci".equals(physical.collation()) || physical.hasTriggers()
                    || physical.hasForeignKeys() || physical.hasCheckConstraints()) {
                throw new DdlConflictException("物理表不满足 ACTIVE 基线,请先对账处理");
            }
            return physical;
        }

        private void validateOwnerColumn(BaasColumn metadataOwner, LogicalColumn physicalOwner) {
            boolean physicalDrift = !Objects.equals(metadataOwner.getDataType(), physicalOwner.type().code())
                    || !Objects.equals(metadataOwner.getLength(), physicalOwner.length())
                    || !Objects.equals(metadataOwner.getScale(), physicalOwner.scale())
                    || !Objects.equals(Boolean.TRUE.equals(metadataOwner.getNullable()), physicalOwner.nullable())
                    || !Objects.equals(metadataOwner.getDefaultValue(), physicalOwner.defaultValue())
                    || !Objects.equals(Boolean.TRUE.equals(metadataOwner.getPk()), physicalOwner.pk())
                    || !Objects.equals(Boolean.TRUE.equals(metadataOwner.getAutoIncrement()),
                            physicalOwner.autoIncrement())
                    || !Objects.equals(metadataOwner.getComment(), physicalOwner.comment());
            if (physicalDrift) {
                throw new DdlConflictException("owner 物理列与平台元数据不一致,请先对账处理");
            }
            if (Boolean.TRUE.equals(metadataOwner.getPk()) || Boolean.TRUE.equals(metadataOwner.getAutoIncrement())
                    || physicalOwner.pk() || physicalOwner.autoIncrement()) {
                throw new BaasBadRequestException("ownerColumn 不得是主键或自增列(spec §8.3)");
            }
            if (!ColumnType.BIGINT.code().equals(metadataOwner.getDataType())
                    || physicalOwner.type() != ColumnType.BIGINT) {
                throw new BaasBadRequestException("owner 列类型必须为 bigint(与 _users.id 一致)");
            }
            if (dto.acl().anon().insert() && !physicalOwner.nullable()) {
                throw new BaasBadRequestException("开启 anon.insert 时 owner 列必须可空(spec §8.3)");
            }
        }

        private void prepareIndexOperation(PhysicalTable physical, LogicalColumn owner, PhysicalIndex existingIndex) {
            if (existingIndex == null) {
                IndexAdmission.validateColumnIndexRequest(ColumnType.BIGINT, null, false, true);
                IndexAdmission.validateFinalStructure(List.of(owner), physical.secondaryIndexes().size() + 1);
                if (physical.indexes().size() + 1 > MYSQL_MAX_TOTAL_INDEXES) {
                    throw new BaasBadRequestException("索引总数超过 MySQL 上限 " + MYSQL_MAX_TOTAL_INDEXES);
                }
                Set<String> existingNames = physical.secondaryIndexes()
                    .stream()
                    .map(PhysicalIndex::indexName)
                    .collect(Collectors.toSet());
                String indexName = IndexNameAllocator.allocate(false, dto.ownerColumn(), existingNames, null).name();
                renderedDdl = DdlRenderer.renderAlterTable(project.getDbName(), tableName,
                        List.of(DdlRenderer.AlterClause.addIndex(false, indexName, dto.ownerColumn())));
                ownerIndexed = true;
                needIndex = true;
                indexOperation = true;
                return;
            }
            ownerUnique = existingIndex.unique();
            ownerIndexed = !existingIndex.unique();
            indexOperation = persistedDdlIntent;
        }

        @Override
        public void inOwnershipTx(DdlWorkContext context) {
            if (context.branch() == OwnershipBranch.NEW_OPERATION || needIndex) {
                tableService.patchDdlLog(context.logId(), tableRow.getId(),
                        needIndex ? renderedDdl.sanitizedSql() : null);
            }
            if (!indexOperation) {
                return;
            }
            int updated = tableMapper.update(null, Wrappers.<BaasTable>lambdaUpdate()
                .eq(BaasTable::getId, tableRow.getId())
                .in(BaasTable::getStatus, TableStatus.ACTIVE.name(), TableStatus.ALTERING.name(),
                        TableStatus.CONFLICT.name())
                .set(BaasTable::getStatus, TableStatus.ALTERING.name()));
            if (updated != 1) {
                throw new DdlConflictException("ACL 补索引状态置位竞争失败");
            }
        }

        @Override
        public ObjectNode perform(DdlWorkContext context) {
            if (indexOperation) {
                applyOrVerifyIndex(context);
            }
            return context.completeSuccess(() -> applyMetadataAndSnapshot(indexOperation));
        }

        private void applyOrVerifyIndex(DdlWorkContext context) {
            if (!context.stepReached(DdlStep.DDL_APPLIED)) {
                PhysicalTable physical = SchemaInspector.readTable(context.projectJdbc(), project.getDbName(),
                        tableName);
                if (physical == null) {
                    throw new DdlConflictException("ACL 补索引物理表不存在");
                }
                OwnerIndexMapping ownerMapping = inspectPhysicalStructure(physical);
                if (ownerMapping.index() == null) {
                    if (!needIndex || renderedDdl == null) {
                        throw new DdlConflictException("ACL 补索引目标在执行前发生漂移");
                    }
                    context.projectJdbc().execute(renderedDdl.sql());
                }
                verifyOwnerIndex(context);
                context.advanceToDdlApplied();
                return;
            }
            verifyOwnerIndex(context);
        }

        private void verifyOwnerIndex(DdlWorkContext context) {
            PhysicalTable physical = SchemaInspector.readTable(context.projectJdbc(), project.getDbName(), tableName);
            if (physical == null) {
                throw new DdlConflictException("ACL owner 单列索引物理目标校验失败");
            }
            OwnerIndexMapping ownerMapping = inspectPhysicalStructure(physical);
            if (ownerMapping.index() == null) {
                throw new DdlConflictException("ACL owner 单列索引物理目标校验失败");
            }
            ownerUnique = ownerMapping.index().unique();
            ownerIndexed = !ownerUnique;
        }

        private OwnerIndexMapping inspectPhysicalStructure(PhysicalTable physical) {
            List<BaasColumn> metadataColumns = columnMapper.selectList(Wrappers.<BaasColumn>lambdaQuery()
                .eq(BaasColumn::getTableId, tableRow.getId())
                .orderByAsc(BaasColumn::getId));
            Map<String, BaasColumn> metadataByName = new LinkedHashMap<>();
            for (BaasColumn metadata : metadataColumns) {
                if (metadataByName.put(metadata.getColumnName(), metadata) != null) {
                    throw new DdlConflictException("平台列元数据存在重复名称,请先对账处理");
                }
            }
            if (metadataByName.size() != physical.columns().size()) {
                throw new DdlConflictException("物理列结构与平台元数据不一致,请先对账处理");
            }

            Map<String, PhysicalIndex> indexesByColumn = new LinkedHashMap<>();
            for (PhysicalIndex index : physical.secondaryIndexes()) {
                if (!SchemaInspector.isMappableSingleColumnIndex(index)) {
                    throw new DdlConflictException("物理索引结构不可映射,请先对账处理");
                }
                String columnName = index.parts().get(0).columnName();
                if (!metadataByName.containsKey(columnName) || indexesByColumn.put(columnName, index) != null) {
                    throw new DdlConflictException("物理索引映射不唯一,请先对账处理");
                }
            }

            OwnerIndexMapping ownerMapping = null;
            for (BaasColumn metadata : metadataColumns) {
                PhysicalColumn physicalColumn = physical.findColumn(metadata.getColumnName());
                if (physicalColumn == null) {
                    throw new DdlConflictException("物理列结构与平台元数据不一致,请先对账处理");
                }
                PhysicalIndex index = indexesByColumn.get(metadata.getColumnName());
                MappingOutcome<LogicalColumn> outcome = LogicalModelMapper.toLogical(physicalColumn,
                        index != null && index.unique(), index != null && !index.unique());
                if (!outcome.ok()) {
                    String subject = metadata.getColumnName().equals(dto.ownerColumn())
                            ? "owner 物理列" : "物理列";
                    throw new DdlConflictException(subject + "不可映射,请先对账处理");
                }
                LogicalColumn logical = outcome.value();
                if (metadata.getColumnName().equals(dto.ownerColumn())) {
                    validateOwnerColumn(metadata, logical);
                    ownerMapping = new OwnerIndexMapping(logical, index);
                }
                else if (!matchesMetadata(metadata, logical)) {
                    throw new DdlConflictException("物理结构与平台元数据不一致,请先对账处理");
                }
            }
            if (ownerMapping == null) {
                throw new BaasBadRequestException("owner 列不存在或元数据不唯一: " + dto.ownerColumn());
            }
            return ownerMapping;
        }

        private boolean matchesMetadata(BaasColumn metadata, LogicalColumn physical) {
            return Objects.equals(metadata.getColumnName(), physical.columnName())
                    && Objects.equals(metadata.getDataType(), physical.type().code())
                    && Objects.equals(metadata.getLength(), physical.length())
                    && Objects.equals(metadata.getScale(), physical.scale())
                    && Objects.equals(Boolean.TRUE.equals(metadata.getNullable()), physical.nullable())
                    && Objects.equals(metadata.getDefaultValue(), physical.defaultValue())
                    && Objects.equals(Boolean.TRUE.equals(metadata.getPk()), physical.pk())
                    && Objects.equals(Boolean.TRUE.equals(metadata.getAutoIncrement()), physical.autoIncrement())
                    && Objects.equals(Boolean.TRUE.equals(metadata.getUnique()), physical.unique())
                    && Objects.equals(Boolean.TRUE.equals(metadata.getIndexed()), physical.indexed())
                    && Objects.equals(metadata.getComment(), physical.comment());
        }

        private ObjectNode applyMetadataAndSnapshot(boolean restoreActive) {
            boolean closeAll = cancelOwner;
            writeAcl("anon", closeAll ? ALL_OFF : dto.acl().anon());
            writeAcl("authenticated", closeAll ? ALL_OFF : dto.acl().authenticated());
            updateOwnerColumnMetadata();
            var update = Wrappers.<BaasTable>lambdaUpdate()
                .eq(BaasTable::getId, tableRow.getId())
                .eq(BaasTable::getStatus,
                        restoreActive ? TableStatus.ALTERING.name() : TableStatus.ACTIVE.name())
                .set(BaasTable::getOwnerColumn, dto.ownerColumn());
            if (restoreActive) {
                update.set(BaasTable::getStatus, TableStatus.ACTIVE.name());
            }
            if (tableMapper.update(null, update) != 1) {
                throw new DdlConflictException("ACL 配置完成状态竞争失败");
            }
            tableService.auditDdl(project.getId(), "ACL_CONFIG", "table=" + tableName + ",owner="
                    + dto.ownerColumn(), closeAll ? "HIGH" : "INFO");
            return aclSnapshot(tableMapper.selectById(tableRow.getId()), closeAll);
        }

        private void updateOwnerColumnMetadata() {
            if (dto.ownerColumn() == null) {
                return;
            }
            int updated = columnMapper.update(null, Wrappers.<BaasColumn>lambdaUpdate()
                .eq(BaasColumn::getTableId, tableRow.getId())
                .eq(BaasColumn::getColumnName, dto.ownerColumn())
                .set(BaasColumn::getUnique, ownerUnique)
                .set(BaasColumn::getIndexed, ownerIndexed));
            if (updated != 1) {
                throw new DdlConflictException("owner 列索引元数据更新竞争失败");
            }
        }

        @Override
        public void onFailureTx(DdlWorkContext context) {
            if (indexOperation) {
                tableMapper.update(null, Wrappers.<BaasTable>lambdaUpdate()
                    .eq(BaasTable::getId, tableRow.getId())
                    .eq(BaasTable::getStatus, TableStatus.ALTERING.name())
                    .set(BaasTable::getStatus, TableStatus.CONFLICT.name()));
            }
        }

        private void writeAcl(String role, AclRoleDTO flags) {
            BaasTableAcl existing = aclMapper.selectOne(Wrappers.<BaasTableAcl>lambdaQuery()
                .eq(BaasTableAcl::getTableId, tableRow.getId())
                .eq(BaasTableAcl::getRole, role));
            if (existing == null) {
                existing = new BaasTableAcl();
                existing.setTableId(tableRow.getId());
                existing.setRole(role);
            }
            existing.setCanSelect(flags.select());
            existing.setCanInsert(flags.insert());
            existing.setCanUpdate(flags.update());
            existing.setCanDelete(flags.delete());
            if (existing.getId() == null) {
                aclMapper.insert(existing);
            }
            else {
                aclMapper.updateById(existing);
            }
        }

    }

    private record OwnerIndexMapping(LogicalColumn logicalColumn, PhysicalIndex index) {
    }

}
