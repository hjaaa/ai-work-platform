/*
 *
 *      Copyright (c) 2018-2026, lengleng All rights reserved.
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
import com.aiwork.baas.ddl.RequestFingerprint;
import com.aiwork.baas.ddl.inspect.DdlTargetMatcher;
import com.aiwork.baas.ddl.inspect.SchemaInspector;
import com.aiwork.baas.ddl.render.DdlRenderer;
import com.aiwork.baas.ddl.type.LogicalColumn;
import com.aiwork.baas.entity.BaasAuditLog;
import com.aiwork.baas.entity.BaasDdlLog;
import com.aiwork.baas.entity.BaasTable;
import com.aiwork.baas.entity.enums.DdlLogStatus;
import com.aiwork.baas.entity.enums.DdlOperationType;
import com.aiwork.baas.entity.enums.DdlStep;
import com.aiwork.baas.entity.enums.TableStatus;
import com.aiwork.baas.exception.DdlConflictException;
import com.aiwork.baas.mapper.BaasAuditLogMapper;
import com.aiwork.baas.mapper.BaasDdlLogMapper;
import com.aiwork.baas.mapper.BaasTableMapper;
import com.aiwork.baas.support.PlanBProjectIntegrationTestSupport;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

class TableCreateRecoveryIntegrationTest extends PlanBProjectIntegrationTestSupport {

    @Autowired
    private TableManagementService tableService;

    @Autowired
    private BaasTableMapper tableMapper;

    @Autowired
    private BaasDdlLogMapper ddlLogMapper;

    @MockitoSpyBean
    private BaasAuditLogMapper auditLogMapper;

    @Override
    protected String projectNamePrefix() {
        return "tbl-recovery";
    }

    @Test
    void ddlAppliedCheckpointWithMissingPhysicalTableRecreatesWithoutRegressingCheckpoint() {
        TableCreateDTO dto = createDto("resume_missing", integerColumn());
        seedFailedCreate(dto, DdlStep.DDL_APPLIED, false);
        doThrow(new IllegalStateException("metadata failure after recreated ddl"))
            .when(auditLogMapper)
            .insert(any(BaasAuditLog.class));
        try {
            assertThatThrownBy(() -> tableService.createTable(project, dto))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("metadata failure after recreated ddl");
        }
        finally {
            Mockito.reset(auditLogMapper);
        }

        BaasDdlLog failed = ddlLog(dto);
        assertThat(failed.getStatus()).isEqualTo(DdlLogStatus.FAILED.name());
        assertThat(failed.getStep()).isEqualTo(DdlStep.DDL_APPLIED.name());
        assertPhysicalTarget(dto);

        ObjectNode snapshot = tableService.createTable(project, dto);

        assertThat(snapshot.get("status").asText()).isEqualTo(TableStatus.ACTIVE.name());
        assertPhysicalTarget(dto);
        assertSuccessfulCheckpoint(dto, 2);
    }

    @Test
    void preparedCheckpointWithExistingExactPhysicalTableSkipsDuplicateCreate() {
        TableCreateDTO dto = createDto("resume_prepared", integerColumn());
        seedFailedCreate(dto, DdlStep.PREPARED, true);

        ObjectNode snapshot = tableService.createTable(project, dto);

        assertThat(snapshot.get("status").asText()).isEqualTo(TableStatus.ACTIVE.name());
        assertPhysicalTarget(dto);
        assertSuccessfulCheckpoint(dto, 1);
    }

    @Test
    void staleRunningWithExactPhysicalTargetIsTakenOver() {
        TableCreateDTO dto = createDto("takeover_running", integerColumn());
        seedCreateState(dto, DdlStep.DDL_APPLIED, true, DdlLogStatus.RUNNING);

        ObjectNode snapshot = tableService.createTable(project, dto);

        assertThat(snapshot.get("status").asText()).isEqualTo(TableStatus.ACTIVE.name());
        assertThat(ddlLog(dto).getOwnerToken()).isNotEqualTo("dead-executor");
        assertPhysicalTarget(dto);
        assertSuccessfulCheckpoint(dto, 0);
    }

    @Test
    void metadataTransactionFailureLeavesDdlAppliedAndRetryCompletesAtomically() {
        TableCreateDTO dto = createDto("metadata_retry", integerColumn());
        doThrow(new IllegalStateException("metadata failure"))
            .when(auditLogMapper)
            .insert(any(BaasAuditLog.class));
        try {
            assertThatThrownBy(() -> tableService.createTable(project, dto))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("metadata failure");
        }
        finally {
            Mockito.reset(auditLogMapper);
        }

        BaasDdlLog failed = ddlLog(dto);
        assertThat(failed.getStatus()).isEqualTo(DdlLogStatus.FAILED.name());
        assertThat(failed.getStep()).isEqualTo(DdlStep.DDL_APPLIED.name());
        assertThat(tableRow(dto).getStatus()).isEqualTo(TableStatus.FAILED.name());
        assertPhysicalTarget(dto);

        ObjectNode retried = tableService.createTable(project, dto);

        assertThat(retried.get("status").asText()).isEqualTo(TableStatus.ACTIVE.name());
        assertPhysicalTarget(dto);
        assertSuccessfulCheckpoint(dto, 1);
    }

    @Test
    void decimalDefaultRoundTripsThroughMysqlAndMatcher() {
        TableCreateDTO dto = createDto("decimal_zero",
                new ColumnDefinitionDTO("rate", "decimal", 2, 2, false,
                        MAPPER.getNodeFactory().numberNode(BigDecimal.ZERO), false, false, null));

        ObjectNode snapshot = tableService.createTable(project, dto);

        assertThat(snapshot.at("/columns/1/defaultValue").asText()).isEqualTo("0.00");
        String physicalDefault = rootJdbc.queryForObject("SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND COLUMN_NAME = 'rate'", String.class,
                project.getDbName(), dto.tableName());
        assertThat(physicalDefault).isEqualTo("0.00");
        assertPhysicalTarget(dto);
    }

    @ParameterizedTest
    @EnumSource(value = DdlStep.class, names = { "PREPARED", "DDL_APPLIED" })
    void decimalTargetMatchesOnPreparedAndDdlAppliedRecovery(DdlStep checkpoint) {
        TableCreateDTO dto = createDto("decimal_resume_" + checkpoint.name().toLowerCase(),
                new ColumnDefinitionDTO("amount", "decimal", 5, 2, false,
                        MAPPER.getNodeFactory().numberNode(new BigDecimal("1.2")), false, false, null));
        seedFailedCreate(dto, checkpoint, true);

        ObjectNode snapshot = tableService.createTable(project, dto);

        assertThat(snapshot.at("/columns/1/defaultValue").asText()).isEqualTo("1.20");
        assertPhysicalTarget(dto);
        assertSuccessfulCheckpoint(dto, 1);
    }

    @Test
    void concurrentSameTableNameHasExactlyOneWinner() throws Exception {
        TableCreateDTO first = createDto("same_name", integerColumn());
        TableCreateDTO second = createDto("same_name", integerColumn());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> firstResult = executor.submit(() -> runConcurrentCreate(first, ready, start));
            Future<Throwable> secondResult = executor.submit(() -> runConcurrentCreate(second, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Throwable> results = new ArrayList<>();
            results.add(firstResult.get(30, TimeUnit.SECONDS));
            results.add(secondResult.get(30, TimeUnit.SECONDS));
            assertThat(results.stream().filter(item -> item == null).count()).isEqualTo(1L);
            assertThat(results.stream().filter(DdlConflictException.class::isInstance).count()).isEqualTo(1L);
        }
        finally {
            executor.shutdownNow();
        }

        assertThat(tableMapper.selectCount(Wrappers.<BaasTable>lambdaQuery()
            .eq(BaasTable::getProjectId, project.getId())
            .eq(BaasTable::getTableName, "same_name"))).isEqualTo(1L);
        assertThat(ddlLogMapper.selectCount(Wrappers.<BaasDdlLog>lambdaQuery()
            .eq(BaasDdlLog::getProjectId, project.getId())
            .eq(BaasDdlLog::getTableName, "same_name")
            .eq(BaasDdlLog::getStatus, DdlLogStatus.SUCCESS.name()))).isEqualTo(1L);
        assertPhysicalTarget(first);
    }

    private Throwable runConcurrentCreate(TableCreateDTO dto, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                return new AssertionError("concurrent create start timeout");
            }
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return exception;
        }
        return catchThrowable(() -> tableService.createTable(project, dto));
    }

    private TableCreateDTO createDto(String tableName, ColumnDefinitionDTO column) {
        return new TableCreateDTO(UUID.randomUUID().toString(), tableName, "恢复测试表", List.of(column));
    }

    private ColumnDefinitionDTO integerColumn() {
        return new ColumnDefinitionDTO("amount", "int", null, null, false, null, false, false, null);
    }

    private void seedFailedCreate(TableCreateDTO dto, DdlStep checkpoint, boolean createPhysical) {
        seedCreateState(dto, checkpoint, createPhysical, DdlLogStatus.FAILED);
    }

    private void seedCreateState(TableCreateDTO dto, DdlStep checkpoint, boolean createPhysical,
            DdlLogStatus status) {
        DdlRenderer.RenderedDdl rendered = rendered(dto);
        BaasTable table = new BaasTable();
        table.setProjectId(project.getId());
        table.setTableName(dto.tableName());
        table.setComment(dto.comment());
        table.setStatus(TableStatus.FAILED.name());
        tableMapper.insert(table);
        if (createPhysical) {
            rootJdbc.execute(rendered.sql());
        }

        BaasDdlLog log = new BaasDdlLog();
        log.setProjectId(project.getId());
        log.setOperationId(dto.operationId());
        log.setOperationType(DdlOperationType.CREATE.code());
        log.setTableName(dto.tableName());
        log.setTableId(table.getId());
        log.setRequestHash(requestHash(dto));
        log.setOwnerToken("dead-executor");
        log.setFenceEpoch(0L);
        log.setDdlText(rendered.sanitizedSql());
        log.setStep(checkpoint.name());
        log.setStatus(status.name());
        log.setRetryCount(0);
        ddlLogMapper.insert(log);
    }

    private DdlRenderer.RenderedDdl rendered(TableCreateDTO dto) {
        List<DdlRenderer.ColumnPlan> plans = dto.columns()
            .stream()
            .map(TableManagementService::toColumnPlan)
            .toList();
        return DdlRenderer.renderCreateTable(project.getDbName(), dto.tableName(), dto.comment(), plans);
    }

    private String requestHash(TableCreateDTO dto) {
        return RequestFingerprint.http("POST", "/studio/projects/" + project.getProjectRef() + "/tables",
                DdlOperationType.CREATE.code(), RequestFingerprint.canonicalBody(dto));
    }

    private void assertSuccessfulCheckpoint(TableCreateDTO dto, int expectedRetryCount) {
        BaasDdlLog log = ddlLog(dto);
        assertThat(log.getStatus()).isEqualTo(DdlLogStatus.SUCCESS.name());
        assertThat(log.getStep()).isEqualTo(DdlStep.METADATA_APPLIED.name());
        assertThat(log.getRetryCount()).isEqualTo(expectedRetryCount);
        assertThat(tableRow(dto).getStatus()).isEqualTo(TableStatus.ACTIVE.name());
    }

    private void assertPhysicalTarget(TableCreateDTO dto) {
        List<LogicalColumn> expected = new ArrayList<>();
        expected.add(TableManagementService.idColumn());
        expected.addAll(dto.columns()
            .stream()
            .map(TableManagementService::toColumnPlan)
            .map(DdlRenderer.ColumnPlan::column)
            .toList());
        assertThat(DdlTargetMatcher.matches(
                SchemaInspector.readTable(rootJdbc, project.getDbName(), dto.tableName()), dto.tableName(),
                dto.comment(), expected)).isTrue();
    }

    private BaasDdlLog ddlLog(TableCreateDTO dto) {
        return ddlLogMapper.selectByProjectAndOperation(project.getId(), dto.operationId());
    }

    private BaasTable tableRow(TableCreateDTO dto) {
        return tableMapper.selectOne(Wrappers.<BaasTable>lambdaQuery()
            .eq(BaasTable::getProjectId, project.getId())
            .eq(BaasTable::getTableName, dto.tableName()));
    }

}
