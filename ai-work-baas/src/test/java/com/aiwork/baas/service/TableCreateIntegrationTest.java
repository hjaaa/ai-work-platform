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
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.BaasTable;
import com.aiwork.baas.entity.enums.ProjectStatus;
import com.aiwork.baas.entity.enums.TableStatus;
import com.aiwork.baas.exception.BaasBadRequestException;
import com.aiwork.baas.exception.DdlConflictException;
import com.aiwork.baas.mapper.BaasDdlLogMapper;
import com.aiwork.baas.mapper.BaasProjectMapper;
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

class TableCreateIntegrationTest extends PlanBProjectIntegrationTestSupport {

    @Override
    protected String projectNamePrefix() {
        return "tbl-create";
    }

    @Autowired
    private TableManagementService tableService;

    @Autowired
    private BaasProjectMapper projectMapper;

    @Autowired
    private BaasTableMapper tableMapper;

    @Autowired
    private BaasDdlLogMapper ddlLogMapper;

    private ColumnDefinitionDTO column(String name, String type, Integer length, Boolean unique, Boolean indexed) {
        return new ColumnDefinitionDTO(name, type, length, null, true, null, unique, indexed, null);
    }

    private TableCreateDTO createDto(String tableName, List<ColumnDefinitionDTO> columns) {
        return new TableCreateDTO(UUID.randomUUID().toString(), tableName, "测试表", columns);
    }

    @Test
    void createTableEndsActiveWithPhysicalBaselineAndMetadata() {
        TableCreateDTO dto = createDto("orders", List.of(
                column("email", "varchar", 255, true, false),
                column("age", "int", null, false, true),
                new ColumnDefinitionDTO("vip", "boolean", null, null, true,
                        MAPPER.getNodeFactory().booleanNode(true), false, false, "会员")));

        ObjectNode snapshot = tableService.createTable(project, dto);

        assertThat(snapshot.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(snapshot.get("columns")).hasSize(4);
        assertThat(snapshot.at("/acl/anon/select").asBoolean()).isFalse();
        assertThat(snapshot.at("/acl/authenticated/insert").asBoolean()).isFalse();

        var row = rootJdbc.queryForMap("SELECT ENGINE, ROW_FORMAT, TABLE_COLLATION "
                + "FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'orders'",
                project.getDbName());
        assertThat(row.get("ENGINE")).isEqualTo("InnoDB");
        assertThat(row.get("ROW_FORMAT")).isEqualTo("Dynamic");
        assertThat(row.get("TABLE_COLLATION")).isEqualTo("utf8mb4_general_ci");

        var vip = rootJdbc.queryForMap("SELECT COLUMN_TYPE, COLUMN_DEFAULT FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'orders' AND COLUMN_NAME = 'vip'", project.getDbName());
        assertThat(vip.get("COLUMN_TYPE")).isEqualTo("tinyint(1)");
        assertThat(vip.get("COLUMN_DEFAULT")).isEqualTo("1");

        BaasTable meta = tableMapper.selectOne(Wrappers.<BaasTable>lambdaQuery()
            .eq(BaasTable::getProjectId, project.getId())
            .eq(BaasTable::getTableName, "orders"));
        assertThat(meta.getStatus()).isEqualTo(TableStatus.ACTIVE.name());
    }

    @Test
    void createTableWithEmptyCommentNormalizesAndEndsActive() {
        TableCreateDTO dto = createDto("notes", List.of(
                new ColumnDefinitionDTO("title", "varchar", 64, null, true, null, false, false, "")));

        ObjectNode snapshot = tableService.createTable(project, dto);

        assertThat(snapshot.get("status").asText()).isEqualTo("ACTIVE");
        BaasTable meta = tableMapper.selectOne(Wrappers.<BaasTable>lambdaQuery()
            .eq(BaasTable::getProjectId, project.getId())
            .eq(BaasTable::getTableName, "notes"));
        assertThat(meta.getStatus()).isEqualTo(TableStatus.ACTIVE.name());
    }

    @Test
    void idempotentReplayReturnsSameSnapshotWithoutSecondExecution() {
        TableCreateDTO dto = createDto("replay_t", List.of(column("name", "varchar", 64, false, false)));
        ObjectNode first = tableService.createTable(project, dto);
        ObjectNode second = tableService.createTable(project, dto);

        assertThat(second).isEqualTo(first);
        Long physicalCount = rootJdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'replay_t'", Long.class, project.getDbName());
        assertThat(physicalCount).isEqualTo(1L);
    }

    @Test
    void sameOperationIdDifferentBodyRejected() {
        TableCreateDTO dto = createDto("fp_t", List.of(column("name", "varchar", 64, false, false)));
        tableService.createTable(project, dto);
        TableCreateDTO tampered = new TableCreateDTO(dto.operationId(), "fp_t2", "改了",
                List.of(column("name", "varchar", 64, false, false)));

        assertThatThrownBy(() -> tableService.createTable(project, tampered))
            .isInstanceOf(DdlConflictException.class);
    }

    @Test
    void duplicateTableNameRejected() {
        tableService.createTable(project, createDto("dup_t", List.of(column("a", "int", null, false, false))));
        assertThatThrownBy(() -> tableService
            .createTable(project, createDto("dup_t", List.of(column("a", "int", null, false, false)))))
            .isInstanceOf(DdlConflictException.class);
    }

    @Test
    void externalPhysicalTableIsNeverAdoptedByCreate() {
        rootJdbc.execute("CREATE TABLE `" + project.getDbName()
                + "`.external_collision (id bigint NOT NULL PRIMARY KEY, alien int) ENGINE=InnoDB");
        String operationId = UUID.randomUUID().toString();

        assertThatThrownBy(() -> tableService.createTable(project, new TableCreateDTO(operationId,
                "external_collision", null, List.of(column("name", "varchar", 64, false, false)))))
            .isInstanceOf(DdlConflictException.class)
            .hasMessageContaining("reconcile");
        assertThat(tableMapper.selectCount(Wrappers.<BaasTable>lambdaQuery()
            .eq(BaasTable::getProjectId, project.getId())
            .eq(BaasTable::getTableName, "external_collision"))).isZero();
        assertThat(ddlLogMapper.selectByProjectAndOperation(project.getId(), operationId)).isNull();
    }

    @Test
    void staticValidationRejectsBadRequestsWithoutLogging() {
        assertThatThrownBy(() -> tableService.createTable(project,
                new TableCreateDTO("not-a-uuid", "bad_uuid", null,
                        List.of(column("a", "int", null, false, false)))))
            .isInstanceOf(BaasBadRequestException.class);
        assertThatThrownBy(() -> tableService.createTable(project,
                createDto("bad1", List.of(column("id", "bigint", null, false, false)))))
            .isInstanceOf(BaasBadRequestException.class);
        assertThatThrownBy(() -> tableService.createTable(project,
                createDto("_sys", List.of(column("a", "int", null, false, false)))))
            .isInstanceOf(BaasBadRequestException.class);
        assertThatThrownBy(() -> tableService.createTable(project,
                createDto("bad2", List.of(column("note", "text", null, false, true)))))
            .isInstanceOf(BaasBadRequestException.class);
        assertThatThrownBy(() -> tableService.createTable(project,
                createDto("bad3", List.of(column("v", "varchar", 769, false, true)))))
            .isInstanceOf(BaasBadRequestException.class);
        assertThatThrownBy(() -> tableService.createTable(project, createDto("bad4",
                List.of(column("a", "int", null, false, false),
                        column("a", "bigint", null, false, false)))))
            .isInstanceOf(BaasBadRequestException.class);
    }

    @Test
    void sixtyFourCharColumnNameGetsLegalIndexName() {
        String longColumn = "c".repeat(64);
        tableService.createTable(project, createDto("long_idx",
                List.of(new ColumnDefinitionDTO(longColumn, "varchar", 128, null, true, null, false, true, null))));

        List<String> indexNames = rootJdbc.queryForList("SELECT DISTINCT INDEX_NAME "
                + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'long_idx' "
                + "AND INDEX_NAME <> 'PRIMARY'", String.class, project.getDbName());
        assertThat(indexNames).hasSize(1);
        assertThat(indexNames.get(0)).startsWith("idx_").hasSizeLessThanOrEqualTo(64);
    }

    @Test
    void nonActiveProjectRejected() {
        BaasProject fake = new BaasProject();
        fake.setProjectRef(UUID.randomUUID().toString().substring(0, 16));
        fake.setName("deleting");
        fake.setDbName("baas_fake_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        fake.setStatus(ProjectStatus.DELETING);
        fake.setOwnerUserId(1L);
        projectMapper.insert(fake);

        assertThatThrownBy(() -> tableService.createTable(fake,
                createDto("nope", List.of(column("a", "int", null, false, false)))))
            .isInstanceOf(DdlConflictException.class);
    }

    @Test
    void listAndDetailReflectMetadata() {
        tableService.createTable(project, createDto("list_t", List.of(column("a", "int", null, false, false))));
        assertThat(tableService.listTables(project).toString()).contains("list_t");
        ObjectNode detail = tableService.getTableSnapshot(project, "list_t");
        assertThat(detail.get("tableName").asText()).isEqualTo("list_t");
        assertThatThrownBy(() -> tableService.getTableSnapshot(project, "no_such"))
            .isInstanceOf(com.aiwork.baas.exception.TableNotFoundException.class);
    }

}
