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
import com.aiwork.baas.controller.dto.ColumnRenameDTO;
import com.aiwork.baas.controller.dto.TableAlterDTO;
import com.aiwork.baas.ddl.engine.DdlWork;
import com.aiwork.baas.ddl.engine.DdlWorkContext;
import com.aiwork.baas.ddl.engine.OwnershipBranch;
import com.aiwork.baas.ddl.index.IndexAdmission;
import com.aiwork.baas.ddl.index.IndexNameAllocator;
import com.aiwork.baas.ddl.inspect.ActualIndexName;
import com.aiwork.baas.ddl.inspect.DdlTargetMatcher;
import com.aiwork.baas.ddl.inspect.PhysicalIndex;
import com.aiwork.baas.ddl.inspect.PhysicalTable;
import com.aiwork.baas.ddl.inspect.SchemaInspector;
import com.aiwork.baas.ddl.render.DdlRenderer;
import com.aiwork.baas.ddl.type.ColumnType;
import com.aiwork.baas.ddl.type.LogicalColumn;
import com.aiwork.baas.ddl.type.TypeCompatibility;
import com.aiwork.baas.entity.BaasColumn;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.BaasTable;
import com.aiwork.baas.entity.BaasTableAcl;
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
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 改表操作(spec §7.3/§9.2/§9.5/§13)：锁内校验实际结构，执行单条 ALTER，支持探测续跑，失败置 CONFLICT。
 *
 * @author ai-work
 * @date 2026/07/18
 */
class AlterTableWork implements DdlWork {

    private static final String STRICT_SQL_MODE = "STRICT_ALL_TABLES";

    /** MySQL 服务层将 PRIMARY 也计入最多 64 个 key 的限制。 */
    private static final int MYSQL_MAX_TOTAL_INDEXES = 64;

    private static final String OWNER_DROP_INTENT_MARKER = "/* BAAS_INTENT:ACL_CLOSED_BY_OWNER_DROP */";

    private final TableManagementService service;

    private final BaasProject project;

    private final String tableName;

    private final TableAlterDTO dto;

    private final BaasTableMapper tableMapper;

    private final BaasColumnMapper columnMapper;

    private final BaasTableAclMapper aclMapper;

    private BaasTable tableRow;

    private List<DdlRenderer.AlterClause> clauses;

    private String targetTableName;

    private Map<String, DdlRenderer.ColumnPlan> modifyPlans;

    private Map<String, DdlRenderer.ColumnPlan> addPlans;

    private List<LogicalColumn> finalColumns;

    private boolean dropsOwnerColumn;

    private String renamedOwnerColumn;

    private boolean ddlAlreadyApplied;

    private boolean lossyOperation;

    AlterTableWork(TableManagementService service, BaasProject project, String tableName, TableAlterDTO dto,
            BaasTableMapper tableMapper, BaasColumnMapper columnMapper, BaasTableAclMapper aclMapper) {
        this.service = service;
        this.project = project;
        this.tableName = tableName;
        this.dto = dto;
        this.tableMapper = tableMapper;
        this.columnMapper = columnMapper;
        this.aclMapper = aclMapper;
    }

    /** 纯 DTO 静态校验，必须在统一执行引擎产生副作用前完成(spec §9.2)。 */
    static void staticValidate(String tableName, TableAlterDTO dto) {
        requireIdentifier(tableName, "表名不合法");
        if (!dto.hasAnyOperation()) {
            throw new BaasBadRequestException("改表请求至少包含一项操作");
        }

        Set<String> operationColumns = new HashSet<>();
        Set<String> renameTargets = new HashSet<>();
        for (ColumnDefinitionDTO add : dto.addColumnsOrEmpty()) {
            requireOnce(operationColumns, TableManagementService.toColumnPlan(add).column().columnName());
        }
        for (String drop : dto.dropColumnsOrEmpty()) {
            requireIdentifier(drop, "列名不合法");
            requireOnce(operationColumns, drop);
        }
        for (ColumnDefinitionDTO modify : dto.modifyColumnsOrEmpty()) {
            requireOnce(operationColumns, TableManagementService.toColumnPlan(modify).column().columnName());
        }
        for (ColumnRenameDTO rename : dto.renameColumnsOrEmpty()) {
            if (rename == null) {
                throw new BaasBadRequestException("列重命名项不能为空");
            }
            requireIdentifier(rename.from(), "源列名不合法");
            requireIdentifier(rename.to(), "目标列名不合法");
            requireOnce(operationColumns, rename.from());
            if (rename.from().equals(rename.to()) || !renameTargets.add(rename.to())) {
                throw new BaasBadRequestException("列重命名目标重复或未变化: " + rename.to());
            }
        }
        validateProtectedId(operationColumns, renameTargets);
        for (String target : renameTargets) {
            if (operationColumns.contains(target)) {
                throw new BaasBadRequestException("重命名目标不得同时参与其他列操作: " + target);
            }
        }
        validateTableRename(tableName, dto.newTableName());
        if (!dto.dropColumnsOrEmpty().isEmpty() && !dto.allowLossyOrDefault()) {
            throw new BaasBadRequestException("删列为破坏性操作，须显式 allowLossy=true 确认");
        }
    }

    private static void validateProtectedId(Set<String> operationColumns, Set<String> renameTargets) {
        if (operationColumns.contains("id") || renameTargets.contains("id")) {
            throw new BaasBadRequestException("主键列 id 不可新增/删除/修改/重命名");
        }
    }

    private static void validateTableRename(String tableName, String newTableName) {
        if (newTableName == null) {
            return;
        }
        requireIdentifier(newTableName, "目标表名不合法");
        if (tableName.equals(newTableName)) {
            throw new BaasBadRequestException("目标表名与原表名相同");
        }
    }

    private static void requireIdentifier(String identifier, String message) {
        try {
            IdentifierValidator.validate(identifier);
        }
        catch (IllegalArgumentException exception) {
            throw new BaasBadRequestException(message);
        }
    }

    private static void requireOnce(Set<String> touched, String columnName) {
        if (!touched.add(columnName)) {
            throw new BaasBadRequestException("同一列在同一请求中只能出现于一种操作: " + columnName);
        }
    }

    @Override
    public void validateInLock(DdlWorkContext context) {
        service.requireProjectActiveInLock(project.getId());
        tableRow = service.findTableRow(project.getId(), tableName);
        if (tableRow == null) {
            throw new TableNotFoundException();
        }
        validateBranchStatus(context);

        List<BaasColumn> metadataColumns = columnMapper.selectList(Wrappers.<BaasColumn>lambdaQuery()
            .eq(BaasColumn::getTableId, tableRow.getId())
            .orderByAsc(BaasColumn::getId));
        Map<String, BaasColumn> byName = metadataColumns.stream()
            .collect(Collectors.toMap(BaasColumn::getColumnName, column -> column, (first, second) -> first,
                    LinkedHashMap::new));
        preparePlans(context, byName);
        targetTableName = dto.newTableName() == null ? tableName : dto.newTableName();
        validateTargetMetadataName();

        PhysicalTable sourcePhysical = SchemaInspector.readTable(context.projectJdbc(), project.getDbName(),
                tableName);
        PhysicalTable targetPhysical = dto.newTableName() == null ? sourcePhysical
                : SchemaInspector.readTable(context.projectJdbc(), project.getDbName(), targetTableName);
        if (dto.newTableName() != null && sourcePhysical != null && targetPhysical != null) {
            throw new DdlConflictException("原表与目标表同时存在，拒绝推断 ALTER 结果");
        }

        List<LogicalColumn> sourceColumns = metadataColumns.stream().map(this::logicalOf).toList();
        boolean sourceMatches = DdlTargetMatcher.matches(sourcePhysical, tableName, tableRow.getComment(),
                sourceColumns);
        String finalComment = dto.comment() == null ? tableRow.getComment() : dto.comment();
        boolean targetMatches = DdlTargetMatcher.matches(targetPhysical, targetTableName, finalComment,
                finalColumns);
        chooseExecutionPath(context, sourcePhysical, targetPhysical, sourceMatches, targetMatches);
    }

    private void validateBranchStatus(DdlWorkContext context) {
        String status = tableRow.getStatus();
        if (context.branch() == OwnershipBranch.NEW_OPERATION) {
            if (!TableStatus.ACTIVE.name().equals(status)) {
                throw new DdlConflictException("表当前状态不允许改表: " + status);
            }
            return;
        }
        if (context.branch() == OwnershipBranch.CLAIM_PENDING) {
            throw new DdlConflictException("改表操作不存在 PENDING 分支");
        }
        if (!TableStatus.ALTERING.name().equals(status) && !TableStatus.CONFLICT.name().equals(status)) {
            throw new DdlConflictException("改表重试要求表处于 ALTERING/CONFLICT 状态");
        }
    }

    private void validateTargetMetadataName() {
        if (dto.newTableName() == null) {
            return;
        }
        BaasTable existingTarget = service.findTableRow(project.getId(), dto.newTableName());
        if (existingTarget != null && !existingTarget.getId().equals(tableRow.getId())) {
            throw new DdlConflictException("目标表名已存在或处于删除保护期: " + dto.newTableName());
        }
    }

    private void chooseExecutionPath(DdlWorkContext context, PhysicalTable sourcePhysical,
            PhysicalTable targetPhysical, boolean sourceMatches, boolean targetMatches) {
        if (context.branch() == OwnershipBranch.NEW_OPERATION) {
            if (dto.newTableName() != null && targetPhysical != null) {
                throw new DdlConflictException("项目库已存在目标同名物理表");
            }
            if (!sourceMatches) {
                throw new DdlConflictException("原表物理结构与平台元数据不一致，请先对账处理");
            }
            buildClauses(sourcePhysical);
            return;
        }

        DdlStep existingStep = DdlStep.valueOf(context.existingLog().getStep());
        if (existingStep.reached(DdlStep.DDL_APPLIED)) {
            if (!targetMatches) {
                throw new DdlConflictException("DDL_APPLIED 检查点与物理目标不一致，拒绝写入元数据");
            }
            ddlAlreadyApplied = true;
            clauses = List.of();
            return;
        }
        if (targetMatches) {
            ddlAlreadyApplied = true;
            clauses = List.of();
            return;
        }
        if (!sourceMatches) {
            throw new DdlConflictException("ALTER 恢复时物理结构既非原始基线也非完整目标");
        }
        buildClauses(sourcePhysical);
    }

    private void preparePlans(DdlWorkContext context, Map<String, BaasColumn> byName) {
        addPlans = new LinkedHashMap<>();
        modifyPlans = new LinkedHashMap<>();
        lossyOperation = !dto.dropColumnsOrEmpty().isEmpty();
        for (ColumnDefinitionDTO add : dto.addColumnsOrEmpty()) {
            DdlRenderer.ColumnPlan plan = TableManagementService.toColumnPlan(add);
            if (byName.containsKey(plan.column().columnName())) {
                throw new BaasBadRequestException("列已存在: " + plan.column().columnName());
            }
            addPlans.put(plan.column().columnName(), plan);
        }
        for (String drop : dto.dropColumnsOrEmpty()) {
            if (!byName.containsKey(drop)) {
                throw new BaasBadRequestException("列不存在: " + drop);
            }
        }
        prepareModifyPlans(byName);
        validateRenameTargets(byName);
        detectOwnerImpacts(context);
        finalColumns = computeFinalColumns(byName);
        validateFinalOwner();
        int finalIndexCount = (int) finalColumns.stream()
            .filter(column -> column.unique() || column.indexed())
            .count();
        IndexAdmission.validateFinalStructure(finalColumns, finalIndexCount);
        long finalTotalIndexCount = finalColumns.stream()
            .filter(column -> column.pk() || column.unique() || column.indexed())
            .count();
        if (finalTotalIndexCount > MYSQL_MAX_TOTAL_INDEXES) {
            throw new BaasBadRequestException("索引总数超过 MySQL 上限 " + MYSQL_MAX_TOTAL_INDEXES);
        }
    }

    private void prepareModifyPlans(Map<String, BaasColumn> byName) {
        for (ColumnDefinitionDTO modify : dto.modifyColumnsOrEmpty()) {
            BaasColumn current = byName.get(modify.columnName());
            if (current == null) {
                throw new BaasBadRequestException("列不存在: " + modify.columnName());
            }
            DdlRenderer.ColumnPlan plan = TableManagementService.toColumnPlan(modify);
            boolean lossless = TypeCompatibility.isLossless(logicalOf(current), plan.column());
            if (!lossless && !dto.allowLossyOrDefault()) {
                throw new BaasBadRequestException("有损类型变更须显式 allowLossy=true 确认: "
                        + modify.columnName());
            }
            lossyOperation = lossyOperation || !lossless;
            modifyPlans.put(modify.columnName(), plan);
        }
    }

    private void validateRenameTargets(Map<String, BaasColumn> byName) {
        for (ColumnRenameDTO rename : dto.renameColumnsOrEmpty()) {
            if (!byName.containsKey(rename.from())) {
                throw new BaasBadRequestException("列不存在: " + rename.from());
            }
            if (byName.containsKey(rename.to())) {
                throw new BaasBadRequestException("目标列名已存在: " + rename.to());
            }
        }
    }

    private void detectOwnerImpacts(DdlWorkContext context) {
        String ownerColumn = tableRow.getOwnerColumn();
        dropsOwnerColumn = hasPersistedOwnerDropIntent(context)
                || ownerColumn != null && dto.dropColumnsOrEmpty().contains(ownerColumn);
        if (ownerColumn == null) {
            return;
        }
        for (ColumnRenameDTO rename : dto.renameColumnsOrEmpty()) {
            if (rename.from().equals(ownerColumn)) {
                renamedOwnerColumn = rename.to();
            }
        }
    }

    private boolean hasPersistedOwnerDropIntent(DdlWorkContext context) {
        return context.existingLog() != null && context.existingLog().getDdlText() != null
                && context.existingLog().getDdlText().startsWith(OWNER_DROP_INTENT_MARKER);
    }

    private void validateFinalOwner() {
        String finalOwner = finalOwnerColumn();
        if (finalOwner == null) {
            return;
        }
        LogicalColumn ownerColumn = finalColumns.stream()
            .filter(column -> column.columnName().equals(finalOwner))
            .findFirst()
            .orElseThrow(() -> new BaasBadRequestException("owner 列缺失"));
        if (ownerColumn.type() != ColumnType.BIGINT) {
            throw new BaasBadRequestException("owner 列类型必须保持 bigint");
        }
        if (!ownerColumn.unique() && !ownerColumn.indexed()) {
            throw new BaasBadRequestException("owner 列必须保留单列索引");
        }
    }

    private String finalOwnerColumn() {
        if (dropsOwnerColumn) {
            return null;
        }
        return renamedOwnerColumn == null ? tableRow.getOwnerColumn() : renamedOwnerColumn;
    }

    private void buildClauses(PhysicalTable physical) {
        clauses = new ArrayList<>();
        Set<String> existingIndexNames = physical.secondaryIndexes()
            .stream()
            .map(PhysicalIndex::indexName)
            .collect(Collectors.toCollection(HashSet::new));
        addColumnClauses(existingIndexNames);
        modifyColumnClauses(physical, existingIndexNames);
        renameColumnClauses(physical, existingIndexNames);
        for (String drop : dto.dropColumnsOrEmpty()) {
            clauses.add(DdlRenderer.AlterClause.dropColumn(drop));
        }
        if (dto.comment() != null) {
            clauses.add(DdlRenderer.AlterClause.tableComment(dto.comment()));
        }
        if (dto.newTableName() != null) {
            clauses.add(DdlRenderer.AlterClause.renameTable(project.getDbName(), dto.newTableName()));
        }
    }

    private void addColumnClauses(Set<String> existingIndexNames) {
        for (DdlRenderer.ColumnPlan plan : addPlans.values()) {
            clauses.add(DdlRenderer.AlterClause.addColumn(plan));
            LogicalColumn column = plan.column();
            if (column.unique() || column.indexed()) {
                IndexNameAllocator.Allocation allocation = IndexNameAllocator.allocate(column.unique(),
                        column.columnName(), existingIndexNames, null);
                existingIndexNames.add(allocation.name());
                clauses.add(DdlRenderer.AlterClause.addIndex(column.unique(), allocation.name(),
                        column.columnName()));
            }
        }
    }

    private void modifyColumnClauses(PhysicalTable physical, Set<String> existingIndexNames) {
        for (Map.Entry<String, DdlRenderer.ColumnPlan> entry : modifyPlans.entrySet()) {
            String columnName = entry.getKey();
            DdlRenderer.ColumnPlan plan = entry.getValue();
            clauses.add(DdlRenderer.AlterClause.modifyColumn(plan));
            PhysicalIndex actual = physical.mappableSingleColumnIndexOn(columnName);
            boolean wantIndex = plan.column().unique() || plan.column().indexed();
            if (actual == null && wantIndex) {
                addAllocatedIndex(existingIndexNames, plan.column(), null);
            }
            else if (actual != null && !wantIndex) {
                clauses.add(DdlRenderer.AlterClause.dropIndex(actualName(actual)));
                existingIndexNames.remove(actual.indexName());
            }
            else if (actual != null && wantIndex && actual.unique() != plan.column().unique()) {
                clauses.add(DdlRenderer.AlterClause.dropIndex(actualName(actual)));
                existingIndexNames.remove(actual.indexName());
                addAllocatedIndex(existingIndexNames, plan.column(), null);
            }
        }
    }

    private void addAllocatedIndex(Set<String> existingIndexNames, LogicalColumn column,
            String currentIndexName) {
        IndexNameAllocator.Allocation allocation = IndexNameAllocator.allocate(column.unique(),
                column.columnName(), existingIndexNames, currentIndexName);
        existingIndexNames.add(allocation.name());
        clauses.add(DdlRenderer.AlterClause.addIndex(column.unique(), allocation.name(), column.columnName()));
    }

    private void renameColumnClauses(PhysicalTable physical, Set<String> existingIndexNames) {
        for (ColumnRenameDTO rename : dto.renameColumnsOrEmpty()) {
            clauses.add(DdlRenderer.AlterClause.renameColumn(rename.from(), rename.to()));
            PhysicalIndex actual = physical.mappableSingleColumnIndexOn(rename.from());
            if (actual == null) {
                continue;
            }
            IndexNameAllocator.Allocation allocation = IndexNameAllocator.allocate(actual.unique(), rename.to(),
                    existingIndexNames, actual.indexName());
            if (!allocation.alreadySatisfied()) {
                clauses.add(DdlRenderer.AlterClause.renameIndex(actualName(actual), allocation.name()));
                existingIndexNames.remove(actual.indexName());
                existingIndexNames.add(allocation.name());
            }
        }
    }

    private ActualIndexName actualName(PhysicalIndex index) {
        try {
            return ActualIndexName.fromInformationSchema(index.indexName());
        }
        catch (IllegalArgumentException exception) {
            throw new DdlConflictException("information_schema 返回了不可安全引用的索引名");
        }
    }

    private LogicalColumn logicalOf(BaasColumn column) {
        return new LogicalColumn(column.getColumnName(), ColumnType.fromCode(column.getDataType()),
                column.getLength(), column.getScale(), Boolean.TRUE.equals(column.getNullable()),
                column.getDefaultValue(), Boolean.TRUE.equals(column.getPk()),
                Boolean.TRUE.equals(column.getAutoIncrement()), Boolean.TRUE.equals(column.getUnique()),
                Boolean.TRUE.equals(column.getIndexed()), column.getComment());
    }

    private List<LogicalColumn> computeFinalColumns(Map<String, BaasColumn> byName) {
        Map<String, LogicalColumn> result = new LinkedHashMap<>();
        for (BaasColumn column : byName.values()) {
            result.put(column.getColumnName(), logicalOf(column));
        }
        for (String drop : dto.dropColumnsOrEmpty()) {
            result.remove(drop);
        }
        for (Map.Entry<String, DdlRenderer.ColumnPlan> entry : modifyPlans.entrySet()) {
            result.put(entry.getKey(), entry.getValue().column());
        }
        for (ColumnRenameDTO rename : dto.renameColumnsOrEmpty()) {
            LogicalColumn moved = result.remove(rename.from());
            result.put(rename.to(), renamedColumn(moved, rename.to()));
        }
        for (Map.Entry<String, DdlRenderer.ColumnPlan> entry : addPlans.entrySet()) {
            if (result.put(entry.getKey(), entry.getValue().column()) != null) {
                throw new BaasBadRequestException("最终列名重复: " + entry.getKey());
            }
        }
        return new ArrayList<>(result.values());
    }

    private LogicalColumn renamedColumn(LogicalColumn source, String targetName) {
        return new LogicalColumn(targetName, source.type(), source.length(), source.scale(), source.nullable(),
                source.defaultValue(), source.pk(), source.autoIncrement(), source.unique(), source.indexed(),
                source.comment());
    }

    @Override
    public void inOwnershipTx(DdlWorkContext context) {
        var update = Wrappers.<BaasTable>lambdaUpdate()
            .eq(BaasTable::getId, tableRow.getId())
            .in(BaasTable::getStatus, TableStatus.ACTIVE.name(), TableStatus.ALTERING.name(),
                    TableStatus.CONFLICT.name())
            .set(BaasTable::getStatus, TableStatus.ALTERING.name());
        if (dropsOwnerColumn) {
            update.set(BaasTable::getOwnerColumn, null);
        }
        if (tableMapper.update(null, update) != 1) {
            throw new DdlConflictException("改表状态置位竞争失败");
        }
        if (dropsOwnerColumn) {
            closeAclForOwnerDrop();
        }
        String sanitizedSql = clauses.isEmpty() ? null
                : DdlRenderer.renderAlterTable(project.getDbName(), tableName, clauses).sanitizedSql();
        if (dropsOwnerColumn && sanitizedSql != null) {
            sanitizedSql = OWNER_DROP_INTENT_MARKER + " " + sanitizedSql;
        }
        service.patchDdlLog(context.logId(), tableRow.getId(), sanitizedSql);
    }

    private void closeAclForOwnerDrop() {
        aclMapper.update(null, Wrappers.<BaasTableAcl>lambdaUpdate()
            .eq(BaasTableAcl::getTableId, tableRow.getId())
            .set(BaasTableAcl::getCanSelect, false)
            .set(BaasTableAcl::getCanInsert, false)
            .set(BaasTableAcl::getCanUpdate, false)
            .set(BaasTableAcl::getCanDelete, false));
    }

    @Override
    public ObjectNode perform(DdlWorkContext context) {
        if (!context.stepReached(DdlStep.DDL_APPLIED)) {
            if (!ddlAlreadyApplied) {
                ensureStrictMode(context);
                DdlRenderer.RenderedDdl rendered = DdlRenderer.renderAlterTable(project.getDbName(), tableName,
                        clauses);
                context.projectJdbc().execute(rendered.sql());
                verifyPhysicalTarget(context);
            }
            context.advanceToDdlApplied();
        }
        else {
            verifyPhysicalTarget(context);
        }
        return context.completeSuccess(() -> {
            applyMetadata();
            return buildSnapshot();
        });
    }

    private void ensureStrictMode(DdlWorkContext context) {
        String sqlMode = context.projectJdbc().queryForObject("SELECT @@SESSION.sql_mode", String.class);
        if (sqlMode == null || (!sqlMode.contains("STRICT_ALL_TABLES")
                && !sqlMode.contains("STRICT_TRANS_TABLES"))) {
            context.projectJdbc().execute("SET SESSION sql_mode = CONCAT_WS(',', @@SESSION.sql_mode, '"
                    + STRICT_SQL_MODE + "')");
        }
    }

    private void verifyPhysicalTarget(DdlWorkContext context) {
        PhysicalTable physical = SchemaInspector.readTable(context.projectJdbc(), project.getDbName(),
                targetTableName);
        String finalComment = dto.comment() == null ? tableRow.getComment() : dto.comment();
        if (!DdlTargetMatcher.matches(physical, targetTableName, finalComment, finalColumns)) {
            throw new DdlConflictException("改表物理目标校验失败，拒绝推进检查点");
        }
    }

    private void applyMetadata() {
        for (String drop : dto.dropColumnsOrEmpty()) {
            columnMapper.delete(Wrappers.<BaasColumn>lambdaQuery()
                .eq(BaasColumn::getTableId, tableRow.getId())
                .eq(BaasColumn::getColumnName, drop));
        }
        for (Map.Entry<String, DdlRenderer.ColumnPlan> entry : modifyPlans.entrySet()) {
            updateColumnMetadata(entry.getKey(), entry.getValue().column());
        }
        for (ColumnRenameDTO rename : dto.renameColumnsOrEmpty()) {
            columnMapper.update(null, Wrappers.<BaasColumn>lambdaUpdate()
                .eq(BaasColumn::getTableId, tableRow.getId())
                .eq(BaasColumn::getColumnName, rename.from())
                .set(BaasColumn::getColumnName, rename.to()));
        }
        for (DdlRenderer.ColumnPlan plan : addPlans.values()) {
            if (columnMapper.selectCount(Wrappers.<BaasColumn>lambdaQuery()
                .eq(BaasColumn::getTableId, tableRow.getId())
                .eq(BaasColumn::getColumnName, plan.column().columnName())) == 0) {
                columnMapper.insert(TableManagementService.columnEntityFrom(tableRow.getId(), plan.column()));
            }
        }
        updateTableMetadata();
        service.auditDdl(project.getId(), "TABLE_ALTER", "table=" + tableName + ",dropColumns="
                + dto.dropColumnsOrEmpty(), lossyOperation ? "HIGH" : "INFO");
    }

    private void updateColumnMetadata(String columnName, LogicalColumn target) {
        int updated = columnMapper.update(null, Wrappers.<BaasColumn>lambdaUpdate()
            .eq(BaasColumn::getTableId, tableRow.getId())
            .eq(BaasColumn::getColumnName, columnName)
            .set(BaasColumn::getDataType, target.type().code())
            .set(BaasColumn::getLength, target.length())
            .set(BaasColumn::getScale, target.scale())
            .set(BaasColumn::getNullable, target.nullable())
            .set(BaasColumn::getDefaultValue, target.defaultValue())
            .set(BaasColumn::getPk, target.pk())
            .set(BaasColumn::getAutoIncrement, target.autoIncrement())
            .set(BaasColumn::getUnique, target.unique())
            .set(BaasColumn::getIndexed, target.indexed())
            .set(BaasColumn::getComment, target.comment()));
        if (updated != 1) {
            throw new DdlConflictException("改列元数据更新竞争失败: " + columnName);
        }
    }

    private void updateTableMetadata() {
        var update = Wrappers.<BaasTable>lambdaUpdate()
            .eq(BaasTable::getId, tableRow.getId())
            .eq(BaasTable::getStatus, TableStatus.ALTERING.name())
            .set(BaasTable::getStatus, TableStatus.ACTIVE.name());
        if (dto.newTableName() != null) {
            update.set(BaasTable::getTableName, dto.newTableName());
        }
        if (dto.comment() != null) {
            update.set(BaasTable::getComment, dto.comment());
        }
        if (dropsOwnerColumn) {
            update.set(BaasTable::getOwnerColumn, null);
        }
        else if (renamedOwnerColumn != null) {
            update.set(BaasTable::getOwnerColumn, renamedOwnerColumn);
        }
        if (tableMapper.update(null, update) != 1) {
            throw new DdlConflictException("改表完成状态竞争失败");
        }
    }

    private ObjectNode buildSnapshot() {
        BaasTable refreshed = tableMapper.selectById(tableRow.getId());
        ObjectNode snapshot = service.snapshotOf(refreshed);
        if (dropsOwnerColumn) {
            snapshot.put("aclClosedByOwnerDrop", true);
        }
        return snapshot;
    }

    @Override
    public void onFailureTx(DdlWorkContext context) {
        tableMapper.update(null, Wrappers.<BaasTable>lambdaUpdate()
            .eq(BaasTable::getId, tableRow.getId())
            .eq(BaasTable::getStatus, TableStatus.ALTERING.name())
            .set(BaasTable::getStatus, TableStatus.CONFLICT.name()));
    }

}
