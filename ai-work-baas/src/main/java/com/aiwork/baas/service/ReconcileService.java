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

import com.aiwork.baas.controller.dto.ReconcileTriggerDTO;
import com.aiwork.baas.ddl.OperationIdValidator;
import com.aiwork.baas.ddl.RequestFingerprint;
import com.aiwork.baas.ddl.engine.DdlExecutionEngine;
import com.aiwork.baas.ddl.engine.DdlOperationSpec;
import com.aiwork.baas.ddl.engine.DdlWork;
import com.aiwork.baas.ddl.engine.DdlWorkContext;
import com.aiwork.baas.ddl.engine.OwnershipBranch;
import com.aiwork.baas.ddl.inspect.AdmissionPredicate;
import com.aiwork.baas.ddl.inspect.MappingOutcome;
import com.aiwork.baas.ddl.inspect.PhysicalDatabase;
import com.aiwork.baas.ddl.inspect.PhysicalTable;
import com.aiwork.baas.ddl.inspect.SchemaInspector;
import com.aiwork.baas.ddl.type.ColumnType;
import com.aiwork.baas.ddl.type.LogicalColumn;
import com.aiwork.baas.entity.BaasAuditLog;
import com.aiwork.baas.entity.BaasColumn;
import com.aiwork.baas.entity.BaasDdlLog;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.BaasTable;
import com.aiwork.baas.entity.BaasTableAcl;
import com.aiwork.baas.entity.enums.DdlLogStatus;
import com.aiwork.baas.entity.enums.DdlOperationType;
import com.aiwork.baas.entity.enums.TableStatus;
import com.aiwork.baas.exception.BaasBadRequestException;
import com.aiwork.baas.exception.DdlConflictException;
import com.aiwork.baas.mapper.BaasAuditLogMapper;
import com.aiwork.baas.mapper.BaasColumnMapper;
import com.aiwork.baas.mapper.BaasDdlLogMapper;
import com.aiwork.baas.mapper.BaasTableAclMapper;
import com.aiwork.baas.mapper.BaasTableMapper;
import com.aiwork.baas.security.CurrentUserProvider;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 表结构对账(spec §9.4):范围仅表结构,以项目库 information_schema 为准。
 * ACL、owner 和密钥是平台操作意图,不参与对账。
 *
 * @author ai-work
 * @date 2026/07/19
 */
@Service
@RequiredArgsConstructor
public class ReconcileService {

    public static final String TRIGGER_MANUAL = "MANUAL";

    public static final String TRIGGER_SCHEDULED = "SCHEDULED";

    /** 定时任务的显式系统审计主体，不冒充项目 owner。 */
    private static final Long SYSTEM_OPERATOR_USER_ID = 0L;

    private static final String AUDIT_ACTION = "DDL_RECONCILE";

    private static final List<String> ACL_ROLES = List.of("anon", "authenticated");

    private static final Set<String> TRIGGER_SOURCES = Set.of(TRIGGER_MANUAL, TRIGGER_SCHEDULED);

    private final DdlExecutionEngine engine;

    private final TableManagementService tableService;

    private final BaasTableMapper tableMapper;

    private final BaasColumnMapper columnMapper;

    private final BaasTableAclMapper aclMapper;

    private final BaasDdlLogMapper ddlLogMapper;

    private final BaasAuditLogMapper auditLogMapper;

    private final CurrentUserProvider userProvider;

    private final ObjectMapper objectMapper;

    public ObjectNode manualReconcile(BaasProject project, ReconcileTriggerDTO dto) {
        if (project == null || dto == null) {
            throw new BaasBadRequestException("对账请求不完整");
        }
        OperationIdValidator.requireUuid(dto.operationId());
        String path = "/studio/projects/" + project.getProjectRef() + "/reconcile";
        String requestHash = RequestFingerprint.http("POST", path, DdlOperationType.RECONCILE.code(),
                RequestFingerprint.canonicalBody(dto));
        return reconcile(project, dto.operationId(), TRIGGER_MANUAL, requestHash);
    }

    public ObjectNode reconcile(BaasProject project, String operationId, String triggerSource, String requestHash) {
        if (project == null || !TRIGGER_SOURCES.contains(triggerSource)) {
            throw new BaasBadRequestException("对账触发来源不合法");
        }
        OperationIdValidator.requireUuid(operationId);
        DdlOperationSpec spec = new DdlOperationSpec(project.getId(), operationId, DdlOperationType.RECONCILE,
                null, null, requestHash, triggerSource, null);
        Long operatorUserId = TRIGGER_SCHEDULED.equals(triggerSource) ? SYSTEM_OPERATOR_USER_ID
                : userProvider.currentUserId();
        return engine.execute(spec, new ReconcileWork(project, triggerSource, operationId, operatorUserId));
    }

    /** 锁内解析遗留 SCHEDULED 操作，存在则原 ID 接管/重试，否则创建本轮新操作。 */
    public ObjectNode scheduledReconcile(BaasProject project) {
        if (project == null) {
            throw new BaasBadRequestException("定时对账项目不能为空");
        }
        return engine.executeResolved(project.getId(), () -> resolveScheduledSpec(project),
                spec -> new ReconcileWork(project, TRIGGER_SCHEDULED, spec.operationId(), SYSTEM_OPERATOR_USER_ID));
    }

    private DdlOperationSpec resolveScheduledSpec(BaasProject project) {
        BaasDdlLog leftover = ddlLogMapper.selectOne(Wrappers.<BaasDdlLog>lambdaQuery()
            .eq(BaasDdlLog::getProjectId, project.getId())
            .eq(BaasDdlLog::getOperationType, DdlOperationType.RECONCILE.code())
            .eq(BaasDdlLog::getTriggerSource, TRIGGER_SCHEDULED)
            .in(BaasDdlLog::getStatus, DdlLogStatus.RUNNING.name(), DdlLogStatus.FAILED.name())
            .last("ORDER BY update_time ASC, id ASC LIMIT 1"));
        if (leftover != null) {
            OperationIdValidator.requireUuid(leftover.getOperationId());
            return scheduledSpec(project, leftover.getOperationId(), leftover.getRequestHash());
        }
        String operationId = UUID.randomUUID().toString();
        return scheduledSpec(project, operationId,
                RequestFingerprint.scheduledReconcile(project.getId(), operationId));
    }

    private DdlOperationSpec scheduledSpec(BaasProject project, String operationId, String requestHash) {
        return new DdlOperationSpec(project.getId(), operationId, DdlOperationType.RECONCILE, null, null,
                requestHash, TRIGGER_SCHEDULED, null);
    }

    private final class ReconcileWork implements DdlWork {

        private final BaasProject project;

        private final String triggerSource;

        private final String operationId;

        private final Long operatorUserId;

        private ReconcileWork(BaasProject project, String triggerSource, String operationId, Long operatorUserId) {
            this.project = project;
            this.triggerSource = triggerSource;
            this.operationId = operationId;
            this.operatorUserId = operatorUserId;
        }

        @Override
        public void validateInLock(DdlWorkContext context) {
            tableService.requireProjectActiveInLock(project.getId());
            if (context.branch() == OwnershipBranch.CLAIM_PENDING) {
                throw new DdlConflictException("reconcile 不存在 PENDING 分支");
            }
            if (TRIGGER_SCHEDULED.equals(triggerSource) && context.branch() == OwnershipBranch.NEW_OPERATION) {
                Long unfinished = ddlLogMapper.selectCount(Wrappers.<BaasDdlLog>lambdaQuery()
                    .eq(BaasDdlLog::getProjectId, project.getId())
                    .eq(BaasDdlLog::getOperationType, DdlOperationType.RECONCILE.code())
                    .eq(BaasDdlLog::getTriggerSource, TRIGGER_SCHEDULED)
                    .ne(BaasDdlLog::getOperationId, operationId)
                    .in(BaasDdlLog::getStatus, DdlLogStatus.RUNNING.name(), DdlLogStatus.FAILED.name()));
                if (unfinished > 0) {
                    throw new DdlConflictException("存在未终结的 SCHEDULED reconcile,本轮跳过");
                }
            }
        }

        @Override
        public ObjectNode perform(DdlWorkContext context) {
            FinalPhysicalSnapshot physicalSnapshot = readFinalPhysicalSnapshot(context);
            List<BaasTable> metadataTables = tableMapper.selectList(Wrappers.<BaasTable>lambdaQuery()
                .eq(BaasTable::getProjectId, project.getId())
                .orderByAsc(BaasTable::getTableName)
                .orderByAsc(BaasTable::getId));
            context.assertLockStillHeld();
            return context.completeSuccess(() -> {
                ObjectNode report = applyRules(physicalSnapshot, metadataTables);
                audit(report);
                return report;
            });
        }

        private void audit(ObjectNode report) {
            String detail = "operationId=" + operationId + ",trigger=" + triggerSource + ",corrected="
                    + report.get("corrected").size() + ",imported=" + report.get("imported").size()
                    + ",recovered=" + report.get("recovered").size() + ",conflicts="
                    + report.get("conflicts").size() + ",rejectedImports=" + report.get("rejectedImports").size();
            BaasAuditLog auditLog = new BaasAuditLog();
            auditLog.setProjectId(project.getId());
            auditLog.setOperatorUserId(operatorUserId);
            auditLog.setAction(AUDIT_ACTION);
            auditLog.setDetail(detail);
            auditLog.setLevel("INFO");
            auditLogMapper.insert(auditLog);
        }

        private FinalPhysicalSnapshot readFinalPhysicalSnapshot(DdlWorkContext context) {
            PhysicalDatabase database = SchemaInspector.readDatabase(context.projectJdbc(), project.getDbName());
            Map<String, PhysicalTable> physicalTables = new TreeMap<>();
            for (PhysicalTable table : SchemaInspector.readAllTables(context.projectJdbc(), project.getDbName())) {
                if (!table.tableName().startsWith("_")) {
                    physicalTables.put(table.tableName(), table);
                }
            }
            return new FinalPhysicalSnapshot(database, Map.copyOf(physicalTables));
        }

        private ObjectNode applyRules(FinalPhysicalSnapshot physicalSnapshot, List<BaasTable> metadataTables) {
            ObjectNode report = objectMapper.createObjectNode();
            ArrayNode corrected = report.putArray("corrected");
            ArrayNode imported = report.putArray("imported");
            ArrayNode recovered = report.putArray("recovered");
            ArrayNode conflicts = report.putArray("conflicts");
            ArrayNode rejectedImports = report.putArray("rejectedImports");

            Map<String, BaasTable> metadataByName = new HashMap<>();
            for (BaasTable table : metadataTables) {
                if (metadataByName.put(table.getTableName(), table) != null) {
                    throw new DdlConflictException("平台表元数据存在重复表名");
                }
                reconcileManagedTable(physicalSnapshot, table, corrected, recovered, conflicts);
            }

            for (Map.Entry<String, PhysicalTable> entry : new TreeMap<>(physicalSnapshot.tables()).entrySet()) {
                if (metadataByName.containsKey(entry.getKey())) {
                    continue;
                }
                MappingOutcome<List<LogicalColumn>> admission = AdmissionPredicate.evaluate(
                        physicalSnapshot.database(), entry.getValue());
                if (!admission.ok()) {
                    appendReason(rejectedImports, entry.getKey(), admission.rejectReason());
                    continue;
                }
                importTable(entry.getValue(), admission.value());
                imported.add(entry.getKey());
            }
            return report;
        }

        private void reconcileManagedTable(FinalPhysicalSnapshot physicalSnapshot, BaasTable table,
                ArrayNode corrected, ArrayNode recovered, ArrayNode conflicts) {
            String originalStatus = table.getStatus();
            boolean processable = TableStatus.ACTIVE.name().equals(originalStatus)
                    || TableStatus.CONFLICT.name().equals(originalStatus);
            if (!processable) {
                return;
            }
            PhysicalTable physical = physicalSnapshot.tables().get(table.getTableName());
            if (physical == null) {
                markConflict(table, "项目库中不存在该表", conflicts);
                return;
            }
            MappingOutcome<List<LogicalColumn>> admission = AdmissionPredicate.evaluate(physicalSnapshot.database(),
                    physical);
            if (!admission.ok()) {
                markConflict(table, admission.rejectReason(), conflicts);
                return;
            }
            if (!ownerConstraintHolds(table, admission.value())) {
                markConflict(table, "owner 安全约束破坏(列缺失/非 bigint/索引丢失)", conflicts);
                return;
            }
            if (correctMetadata(table, physical, admission.value())) {
                corrected.add(table.getTableName());
            }
            if (TableStatus.CONFLICT.name().equals(originalStatus)) {
                int updated = tableMapper.update(null, Wrappers.<BaasTable>lambdaUpdate()
                    .eq(BaasTable::getId, table.getId())
                    .eq(BaasTable::getProjectId, project.getId())
                    .eq(BaasTable::getStatus, TableStatus.CONFLICT.name())
                    .set(BaasTable::getStatus, TableStatus.ACTIVE.name()));
                if (updated != 1) {
                    throw new DdlConflictException("对账恢复 ACTIVE 状态竞争失败");
                }
                recovered.add(table.getTableName());
            }
        }

        private void markConflict(BaasTable table, String reason, ArrayNode conflicts) {
            int updated = tableMapper.update(null, Wrappers.<BaasTable>lambdaUpdate()
                .eq(BaasTable::getId, table.getId())
                .eq(BaasTable::getProjectId, project.getId())
                .in(BaasTable::getStatus, TableStatus.ACTIVE.name(), TableStatus.CONFLICT.name())
                .set(BaasTable::getStatus, TableStatus.CONFLICT.name()));
            if (updated != 1) {
                throw new DdlConflictException("对账标记 CONFLICT 状态竞争失败");
            }
            appendReason(conflicts, table.getTableName(), reason);
        }

        private void appendReason(ArrayNode target, String tableName, String reason) {
            ObjectNode item = target.addObject();
            item.put("tableName", tableName);
            item.put("reason", reason);
        }

        private boolean ownerConstraintHolds(BaasTable table, List<LogicalColumn> logicalColumns) {
            if (table.getOwnerColumn() == null) {
                return true;
            }
            return logicalColumns.stream()
                .filter(column -> column.columnName().equals(table.getOwnerColumn()))
                .anyMatch(column -> column.type() == ColumnType.BIGINT && !column.pk() && !column.autoIncrement()
                        && (column.unique() || column.indexed()));
        }

        private boolean correctMetadata(BaasTable table, PhysicalTable physical,
                List<LogicalColumn> logicalColumns) {
            List<BaasColumn> existingColumns = columnMapper.selectList(Wrappers.<BaasColumn>lambdaQuery()
                .eq(BaasColumn::getTableId, table.getId())
                .orderByAsc(BaasColumn::getId));
            Map<String, BaasColumn> existingByName = new HashMap<>();
            for (BaasColumn column : existingColumns) {
                if (existingByName.put(column.getColumnName(), column) != null) {
                    throw new DdlConflictException("平台列元数据存在重复列名");
                }
            }

            boolean changed = updateTableComment(table, physical);
            for (LogicalColumn logical : logicalColumns) {
                BaasColumn current = existingByName.remove(logical.columnName());
                BaasColumn desired = TableManagementService.columnEntityFrom(table.getId(), logical);
                if (current == null) {
                    columnMapper.insert(desired);
                    changed = true;
                }
                else if (differs(current, desired)) {
                    updateColumn(table, current, desired);
                    changed = true;
                }
            }
            for (BaasColumn orphan : existingByName.values()) {
                int deleted = columnMapper.delete(Wrappers.<BaasColumn>lambdaQuery()
                    .eq(BaasColumn::getId, orphan.getId())
                    .eq(BaasColumn::getTableId, table.getId()));
                if (deleted != 1) {
                    throw new DdlConflictException("对账删除孤立列元数据竞争失败");
                }
                changed = true;
            }
            return changed;
        }

        private boolean updateTableComment(BaasTable table, PhysicalTable physical) {
            String physicalComment = emptyToNull(physical.tableComment());
            if (Objects.equals(table.getComment(), physicalComment)) {
                return false;
            }
            int updated = tableMapper.update(null, Wrappers.<BaasTable>lambdaUpdate()
                .eq(BaasTable::getId, table.getId())
                .eq(BaasTable::getProjectId, project.getId())
                .in(BaasTable::getStatus, TableStatus.ACTIVE.name(), TableStatus.CONFLICT.name())
                .set(BaasTable::getComment, physicalComment));
            if (updated != 1) {
                throw new DdlConflictException("对账表注释更新竞争失败");
            }
            return true;
        }

        private void updateColumn(BaasTable table, BaasColumn current, BaasColumn desired) {
            int updated = columnMapper.update(null, Wrappers.<BaasColumn>lambdaUpdate()
                .eq(BaasColumn::getId, current.getId())
                .eq(BaasColumn::getTableId, table.getId())
                .eq(BaasColumn::getColumnName, current.getColumnName())
                .set(BaasColumn::getDataType, desired.getDataType())
                .set(BaasColumn::getLength, desired.getLength())
                .set(BaasColumn::getScale, desired.getScale())
                .set(BaasColumn::getNullable, desired.getNullable())
                .set(BaasColumn::getDefaultValue, desired.getDefaultValue())
                .set(BaasColumn::getPk, desired.getPk())
                .set(BaasColumn::getAutoIncrement, desired.getAutoIncrement())
                .set(BaasColumn::getUnique, desired.getUnique())
                .set(BaasColumn::getIndexed, desired.getIndexed())
                .set(BaasColumn::getComment, desired.getComment()));
            if (updated != 1) {
                throw new DdlConflictException("对账列元数据更新竞争失败");
            }
        }

        private boolean differs(BaasColumn current, BaasColumn desired) {
            return !Objects.equals(current.getDataType(), desired.getDataType())
                    || !Objects.equals(current.getLength(), desired.getLength())
                    || !Objects.equals(current.getScale(), desired.getScale())
                    || !Objects.equals(current.getNullable(), desired.getNullable())
                    || !Objects.equals(current.getDefaultValue(), desired.getDefaultValue())
                    || !Objects.equals(current.getPk(), desired.getPk())
                    || !Objects.equals(current.getAutoIncrement(), desired.getAutoIncrement())
                    || !Objects.equals(current.getUnique(), desired.getUnique())
                    || !Objects.equals(current.getIndexed(), desired.getIndexed())
                    || !Objects.equals(current.getComment(), desired.getComment());
        }

        private void importTable(PhysicalTable physical, List<LogicalColumn> logicalColumns) {
            BaasTable table = new BaasTable();
            table.setProjectId(project.getId());
            table.setTableName(physical.tableName());
            table.setComment(emptyToNull(physical.tableComment()));
            table.setStatus(TableStatus.ACTIVE.name());
            tableMapper.insert(table);
            for (LogicalColumn logical : logicalColumns) {
                columnMapper.insert(TableManagementService.columnEntityFrom(table.getId(), logical));
            }
            for (String role : ACL_ROLES) {
                BaasTableAcl acl = new BaasTableAcl();
                acl.setTableId(table.getId());
                acl.setRole(role);
                acl.setCanSelect(false);
                acl.setCanInsert(false);
                acl.setCanUpdate(false);
                acl.setCanDelete(false);
                aclMapper.insert(acl);
            }
        }

        private String emptyToNull(String value) {
            return value == null || value.isEmpty() ? null : value;
        }

    }

    private record FinalPhysicalSnapshot(PhysicalDatabase database, Map<String, PhysicalTable> tables) {
    }

}
