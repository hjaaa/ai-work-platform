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

import com.aiwork.baas.controller.dto.AclConfigDTO;
import com.aiwork.baas.controller.dto.AclPutDTO;
import com.aiwork.baas.controller.dto.AclRoleDTO;
import com.aiwork.baas.controller.dto.ColumnDefinitionDTO;
import com.aiwork.baas.controller.dto.TableAlterDTO;
import com.aiwork.baas.controller.dto.TableCreateDTO;
import com.aiwork.baas.ddl.lock.AdvisoryLockTemplate;
import com.aiwork.baas.entity.enums.DdlLogStatus;
import com.aiwork.baas.exception.BaasBadRequestException;
import com.aiwork.baas.exception.DdlConflictException;
import com.aiwork.baas.mapper.BaasDdlLogMapper;
import com.aiwork.baas.support.PlanBProjectIntegrationTestSupport;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndexBoundaryIntegrationTest extends PlanBProjectIntegrationTestSupport {

    @Autowired
    private TableManagementService tableService;

    @Autowired
    private AclConfigService aclService;

    @Autowired
    private BaasDdlLogMapper ddlLogMapper;

    @Autowired
    private AdvisoryLockTemplate advisoryLockTemplate;

    @Override
    protected String projectNamePrefix() {
        return "idxb";
    }

    @Test
    void createWithSixtyThreeSecondaryIndexesSucceedsAtTotalKeyLimit() {
        String operationId = UUID.randomUUID().toString();

        tableService.createTable(project,
                new TableCreateDTO(operationId, "idx_create_63", null, indexedColumns(63)));

        assertThat(totalIndexCount("idx_create_63")).isEqualTo(64L);
        assertThat(ddlLogMapper.selectByProjectAndOperation(project.getId(), operationId).getStatus())
                .isEqualTo(DdlLogStatus.SUCCESS.name());
    }

    @Test
    void createWithSixtyFourSecondaryIndexesRejectedWithoutLogOrPhysicalTable() {
        String operationId = UUID.randomUUID().toString();

        assertThatThrownBy(() -> tableService.createTable(project,
                new TableCreateDTO(operationId, "idx_create_64", null, indexedColumns(64))))
                .isInstanceOf(BaasBadRequestException.class);

        assertThat(ddlLogMapper.selectByProjectAndOperation(project.getId(), operationId)).isNull();
        assertThat(rootJdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'idx_create_64'", Long.class, project.getDbName()))
                .isZero();
    }

    @Test
    void sixtyFourthTotalIndexAcceptedSixtyFifthRejected() {
        List<ColumnDefinitionDTO> columns = new ArrayList<>();
        for (int i = 1; i <= 62; i++) {
            columns.add(new ColumnDefinitionDTO("c%02d".formatted(i), "int", null, null, true, null, false, true,
                    null));
        }
        columns.add(new ColumnDefinitionDTO("d1", "int", null, null, true, null, false, false, null));
        columns.add(new ColumnDefinitionDTO("d2", "int", null, null, true, null, false, false, null));
        tableService.createTable(project,
                new TableCreateDTO(UUID.randomUUID().toString(), "idx_limit", null, columns));

        tableService.alterTable(project, "idx_limit", new TableAlterDTO(UUID.randomUUID().toString(), null, null,
                null, List.of(new ColumnDefinitionDTO("e1", "int", null, null, true, null, false, true, null)),
                null, null, null));
        String operationId = UUID.randomUUID().toString();
        assertThatThrownBy(() -> tableService.alterTable(project, "idx_limit",
                new TableAlterDTO(operationId, null, null, null,
                        List.of(new ColumnDefinitionDTO("e2", "int", null, null, true, null, false, true, null)),
                        null, null, null)))
                .isInstanceOf(BaasBadRequestException.class);
        assertThat(ddlLogMapper.selectByProjectAndOperation(project.getId(), operationId)).isNull();
        assertThat(rootJdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'idx_limit' AND COLUMN_NAME = 'e2'", Long.class,
                project.getDbName())).isZero();
    }

    @Test
    void wideningIndexedVarcharBeyond768Rejected() {
        tableService.createTable(project, new TableCreateDTO(UUID.randomUUID().toString(), "idx_wide", null,
                List.of(new ColumnDefinitionDTO("v", "varchar", 768, null, true, null, false, true, null))));
        String operationId = UUID.randomUUID().toString();
        List<String> indexNamesBefore = indexNamesOn("idx_wide", "v");

        assertThatThrownBy(() -> tableService.alterTable(project, "idx_wide",
                new TableAlterDTO(operationId, null, null, null, null, null,
                        List.of(new ColumnDefinitionDTO("v", "varchar", 800, null, true, null, false, true, null)),
                        null)))
                .isInstanceOf(BaasBadRequestException.class);
        assertThat(ddlLogMapper.selectByProjectAndOperation(project.getId(), operationId)).isNull();
        assertThat(rootJdbc.queryForObject("SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'idx_wide' AND COLUMN_NAME = 'v'", Long.class,
                project.getDbName())).isEqualTo(768L);
        assertThat(indexNamesOn("idx_wide", "v")).isEqualTo(indexNamesBefore);
    }

    @Test
    void aclOwnerIndexAtSixtyFifthTotalKeyRejectedWithoutSideEffects() {
        List<ColumnDefinitionDTO> columns = new ArrayList<>(indexedColumns(63));
        columns.add(new ColumnDefinitionDTO("owner_id", "bigint", null, null, true, null, false, false, null));
        tableService.createTable(project,
                new TableCreateDTO(UUID.randomUUID().toString(), "idx_acl_limit", null, columns));
        String operationId = UUID.randomUUID().toString();
        AclRoleDTO allOff = new AclRoleDTO(false, false, false, false);
        AclPutDTO dto = new AclPutDTO(operationId, new AclConfigDTO(allOff, allOff), "owner_id");

        assertThatThrownBy(() -> aclService.putAcl(project, "idx_acl_limit", dto))
                .isInstanceOf(BaasBadRequestException.class);

        assertThat(ddlLogMapper.selectByProjectAndOperation(project.getId(), operationId)).isNull();
        assertThat(totalIndexCount("idx_acl_limit")).isEqualTo(64L);
        assertThat(indexNamesOn("idx_acl_limit", "owner_id")).isEmpty();
        assertThat(tableService.getTableSnapshot(project, "idx_acl_limit").get("ownerColumn").isNull()).isTrue();
    }

    @Test
    void foreignIndexNameOccupationFallsBackToHashedName() {
        tableService.createTable(project, new TableCreateDTO(UUID.randomUUID().toString(), "idx_occ", null,
                List.of(new ColumnDefinitionDTO("email", "varchar", 128, null, true, null, false, false, null),
                        new ColumnDefinitionDTO("other", "varchar", 128, null, true, null, false, true, null))));
        rootJdbc.execute("ALTER TABLE `" + project.getDbName()
                + "`.idx_occ RENAME INDEX idx_other TO idx_email");

        tableService.alterTable(project, "idx_occ", new TableAlterDTO(UUID.randomUUID().toString(), null, null,
                null, null, null,
                List.of(new ColumnDefinitionDTO("email", "varchar", 128, null, true, null, false, true, null)),
                null));

        String indexName = rootJdbc.queryForObject("SELECT INDEX_NAME FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'idx_occ' AND COLUMN_NAME = 'email'", String.class,
                project.getDbName());
        assertThat(indexName).startsWith("idx_email_").isNotEqualTo("idx_email");
    }

    @Test
    void longCommentAndDefaultSurviveMetadataDoubleWrite() {
        String longComment = "备".repeat(600);
        String longDefault = "d".repeat(300);
        tableService.createTable(project, new TableCreateDTO(UUID.randomUUID().toString(), "idx_long",
                "表".repeat(500), List.of(new ColumnDefinitionDTO("v", "varchar", 1024, null, true,
                        MAPPER.getNodeFactory().textNode(longDefault), false, false, longComment))));

        ObjectNode snapshot = tableService.getTableSnapshot(project, "idx_long");
        assertThat(snapshot.get("comment").asText()).hasSize(500);
        assertThat(snapshot.get("columns").get(1).get("comment").asText()).isEqualTo(longComment);
        assertThat(snapshot.get("columns").get(1).get("defaultValue").asText()).isEqualTo(longDefault);
    }

    @Test
    void advisoryLockHeldExternallyYields409() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> holderFailure = new AtomicReference<>();
        Thread holder = new Thread(() -> {
            try {
                advisoryLockTemplate.executeWithLock(project.getId(), connection -> {
                    locked.countDown();
                    release.await(20, TimeUnit.SECONDS);
                    return null;
                });
            } catch (Throwable throwable) {
                holderFailure.set(throwable);
            }
        }, "ddl-advisory-lock-holder");
        holder.start();
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            assertThatThrownBy(() -> tableService.createTable(project,
                    new TableCreateDTO(UUID.randomUUID().toString(), "idx_adv", null,
                            List.of(new ColumnDefinitionDTO("a", "int", null, null, true, null, false, false,
                                    null)))))
                    .isInstanceOf(DdlConflictException.class);
        } finally {
            release.countDown();
            holder.join(20000);
        }
        assertThat(holderFailure.get()).isNull();
    }

    private List<ColumnDefinitionDTO> indexedColumns(int count) {
        List<ColumnDefinitionDTO> columns = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            columns.add(new ColumnDefinitionDTO("c%02d".formatted(i), "int", null, null, true, null, false, true,
                    null));
        }
        return columns;
    }

    private long totalIndexCount(String table) {
        return rootJdbc.queryForObject("SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?", Long.class, project.getDbName(), table);
    }

    private List<String> indexNamesOn(String table, String column) {
        return rootJdbc.queryForList("SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND COLUMN_NAME = ? ORDER BY INDEX_NAME",
                String.class, project.getDbName(), table, column);
    }

}
