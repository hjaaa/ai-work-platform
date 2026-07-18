/*
 *
 *      Copyright (c) 2018-2025, lengleng All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 */

package com.aiwork.baas.service;

import com.aiwork.baas.controller.dto.ColumnDefinitionDTO;
import com.aiwork.baas.controller.dto.TableCreateDTO;
import com.aiwork.baas.ddl.RequestFingerprint;
import com.aiwork.baas.entity.BaasDdlLog;
import com.aiwork.baas.entity.BaasTable;
import com.aiwork.baas.entity.enums.DdlLogStatus;
import com.aiwork.baas.entity.enums.DdlOperationType;
import com.aiwork.baas.entity.enums.DdlStep;
import com.aiwork.baas.entity.enums.TableStatus;
import com.aiwork.baas.exception.BaasBadRequestException;
import com.aiwork.baas.exception.DdlConflictException;
import com.aiwork.baas.mapper.BaasDdlLogMapper;
import com.aiwork.baas.mapper.BaasTableMapper;
import com.aiwork.baas.support.PlanBProjectIntegrationTestSupport;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TableDropIntegrationTest extends PlanBProjectIntegrationTestSupport {

    @Override
    protected String projectNamePrefix() {
        return "tbl-drop";
    }

    @Autowired
    private TableManagementService tableService;

    @Autowired
    private BaasTableMapper tableMapper;

    @Autowired
    private BaasDdlLogMapper ddlLogMapper;

    private String createTable(String name) {
        tableService.createTable(project, new TableCreateDTO(UUID.randomUUID().toString(), name, null,
                List.of(new ColumnDefinitionDTO("name", "varchar", 64, null, true, null, false, false, null))));
        return name;
    }

    private BaasTable tableRow(String name) {
        return tableMapper.selectOne(Wrappers.<BaasTable>lambdaQuery()
            .eq(BaasTable::getProjectId, project.getId())
            .eq(BaasTable::getTableName, name));
    }

    @Test
    void dropWritesTombstoneAndPendingCleanupInOneTransaction() {
        String table = createTable("drop_a");
        Long tableId = tableRow(table).getId();
        String operationId = UUID.randomUUID().toString();

        ObjectNode snapshot = tableService.dropTable(project, table, operationId);

        assertThat(snapshot.get("status").asText()).isEqualTo("DELETED");
        assertThat(snapshot.get("cleanupOperationId").asText()).isNotBlank();
        BaasTable row = tableRow(table);
        assertThat(row.getStatus()).isEqualTo(TableStatus.DELETED.name());
        assertThat(row.getDeleteAfter()).isNotNull();

        BaasDdlLog cleanup = ddlLogMapper.selectByProjectAndOperation(project.getId(),
                snapshot.get("cleanupOperationId").asText());
        assertThat(cleanup.getStatus()).isEqualTo(DdlLogStatus.PENDING.name());
        assertThat(cleanup.getOperationType()).isEqualTo(DdlOperationType.CLEANUP_DROP.code());
        assertThat(cleanup.getTableId()).isEqualTo(tableId);
        assertThat(cleanup.getRequestHash()).isEqualTo(RequestFingerprint.cleanupDrop(project.getId(), tableId,
                row.getDeleteAfter()));
        assertThat(cleanup.getOwnerToken()).isNull();
        assertThat(cleanup.getFenceEpoch()).isNull();
        BaasDdlLog drop = ddlLogMapper.selectByProjectAndOperation(project.getId(), operationId);
        assertThat(drop.getStatus()).isEqualTo(DdlLogStatus.SUCCESS.name());
        assertThat(drop.getStep()).isEqualTo(DdlStep.METADATA_APPLIED.name());

        assertThat(rootJdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?", Long.class, project.getDbName(), table))
            .isEqualTo(1L);
    }

    @Test
    void dropReplayReturnsSameSnapshotWithoutSecondPendingRecord() {
        String table = createTable("drop_b");
        String operationId = UUID.randomUUID().toString();
        ObjectNode first = tableService.dropTable(project, table, operationId);
        ObjectNode second = tableService.dropTable(project, table, operationId);

        assertThat(second).isEqualTo(first);
        Long pendingCount = ddlLogMapper.selectCount(Wrappers.<BaasDdlLog>lambdaQuery()
            .eq(BaasDdlLog::getProjectId, project.getId())
            .eq(BaasDdlLog::getTableId, tableRow(table).getId())
            .eq(BaasDdlLog::getOperationType, DdlOperationType.CLEANUP_DROP.code()));
        assertThat(pendingCount).isEqualTo(1L);
    }

    @Test
    void tombstoneBlocksRecreatingSameName() {
        String table = createTable("drop_c");
        tableService.dropTable(project, table, UUID.randomUUID().toString());

        assertThatThrownBy(() -> createTable("drop_c")).isInstanceOf(DdlConflictException.class);
    }

    @Test
    void failedAndConflictTablesAreDroppable() {
        String table = createTable("drop_d");
        tableMapper.update(null, Wrappers.<BaasTable>lambdaUpdate()
            .eq(BaasTable::getId, tableRow(table).getId())
            .set(BaasTable::getStatus, TableStatus.FAILED.name()));

        ObjectNode snapshot = tableService.dropTable(project, table, UUID.randomUUID().toString());
        assertThat(snapshot.get("status").asText()).isEqualTo("DELETED");
    }

    @Test
    void conflictTableIsDroppable() {
        String table = createTable("drop_conflict");
        tableMapper.update(null, Wrappers.<BaasTable>lambdaUpdate()
            .eq(BaasTable::getId, tableRow(table).getId())
            .set(BaasTable::getStatus, TableStatus.CONFLICT.name()));

        ObjectNode snapshot = tableService.dropTable(project, table, UUID.randomUUID().toString());

        assertThat(snapshot.get("status").asText()).isEqualTo("DELETED");
    }

    @Test
    void sameOperationIdDroppingDifferentTableRejectedByFingerprint() {
        String tableA = createTable("drop_e1");
        String tableB = createTable("drop_e2");
        String operationId = UUID.randomUUID().toString();
        tableService.dropTable(project, tableA, operationId);

        assertThatThrownBy(() -> tableService.dropTable(project, tableB, operationId))
            .isInstanceOf(DdlConflictException.class);
    }

    @Test
    void alteringTableNotDroppable() {
        String table = createTable("drop_f");
        tableMapper.update(null, Wrappers.<BaasTable>lambdaUpdate()
            .eq(BaasTable::getId, tableRow(table).getId())
            .set(BaasTable::getStatus, TableStatus.ALTERING.name()));

        assertThatThrownBy(() -> tableService.dropTable(project, table, UUID.randomUUID().toString()))
            .isInstanceOf(DdlConflictException.class);
    }

    @Test
    void malformedOperationIdRejectedBeforeLogCreation() {
        String table = createTable("drop_uuid");

        assertThatThrownBy(() -> tableService.dropTable(project, table, "not-a-uuid"))
            .isInstanceOf(BaasBadRequestException.class)
            .hasMessageContaining("UUID");
        assertThat(ddlLogMapper.selectByProjectAndOperation(project.getId(), "not-a-uuid")).isNull();
        assertThat(tableRow(table).getStatus()).isEqualTo(TableStatus.ACTIVE.name());
    }

}
