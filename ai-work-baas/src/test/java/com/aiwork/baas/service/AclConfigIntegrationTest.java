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

import com.aiwork.baas.controller.dto.AclConfigDTO;
import com.aiwork.baas.controller.dto.AclPutDTO;
import com.aiwork.baas.controller.dto.AclRoleDTO;
import com.aiwork.baas.controller.dto.ColumnDefinitionDTO;
import com.aiwork.baas.controller.dto.TableAlterDTO;
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
import com.aiwork.baas.support.PlanBProjectIntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

class AclConfigIntegrationTest extends PlanBProjectIntegrationTestSupport {

    private static final AclRoleDTO ALL_OFF = new AclRoleDTO(false, false, false, false);

    private static final AclRoleDTO READ_ONLY = new AclRoleDTO(true, false, false, false);

    @Autowired
    private AclConfigService aclService;

    @MockitoSpyBean
    private TableManagementService tableService;

    @Autowired
    private BaasDdlLogMapper ddlLogMapper;

    @Override
    protected String projectNamePrefix() {
        return "acl";
    }

    @Test
    void pureSwitchBranchWritesAclWithoutTouchingTableStatus() {
        String table = createTableWithOwnerCandidate("acl_sw", true);
        AclPutDTO dto = put(READ_ONLY, READ_ONLY, null);

        ObjectNode snapshot = aclService.putAcl(project, table, dto);

        assertThat(snapshot.at("/acl/anon/select").asBoolean()).isTrue();
        assertThat(tableService.getTableSnapshot(project, table).get("status").asText()).isEqualTo("ACTIVE");
        BaasDdlLog logRecord = ddlLogMapper.selectByProjectAndOperation(project.getId(), dto.operationId());
        assertThat(logRecord.getStep()).isEqualTo(DdlStep.METADATA_APPLIED.name());
        assertThat(logRecord.getDdlText()).isNull();
    }

    @Test
    void settingOwnerBuildsIndexFromPhysicalStateAndEndsActive() {
        String table = createTableWithOwnerCandidate("acl_own", true);
        assertThat(indexCountOn(table, "owner_id")).isZero();

        ObjectNode snapshot = aclService.putAcl(project, table, put(ALL_OFF, READ_ONLY, "owner_id"));

        assertThat(snapshot.get("ownerColumn").asText()).isEqualTo("owner_id");
        assertThat(indexCountOn(table, "owner_id")).isEqualTo(1L);
        ObjectNode tableSnapshot = tableService.getTableSnapshot(project, table);
        assertThat(tableSnapshot.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(tableSnapshot.withArray("columns").findValue("columnName").asText()).isEqualTo("id");
        assertThat(tableSnapshot.withArray("columns").findValuesAsText("columnName")).contains("owner_id");
        assertThat(tableSnapshot.withArray("columns").findParents("columnName"))
            .filteredOn(node -> "owner_id".equals(node.get("columnName").asText()))
            .allSatisfy(node -> assertThat(node.get("indexed").asBoolean()).isTrue());
        aclService.putAcl(project, table, put(ALL_OFF, READ_ONLY, "owner_id"));
        assertThat(indexCountOn(table, "owner_id")).isEqualTo(1L);
    }

    @Test
    void ownerIdColumnRejectedWithoutSideEffects() {
        String table = createTableWithOwnerCandidate("acl_id", true);

        assertThatThrownBy(() -> aclService.putAcl(project, table, put(ALL_OFF, READ_ONLY, "id")))
            .isInstanceOf(BaasBadRequestException.class);

        assertThat(tableService.getTableSnapshot(project, table).at("/acl/authenticated/select").asBoolean())
            .isFalse();
        assertThat(indexCountOn(table, "id")).isEqualTo(1L);
    }

    @Test
    void nonBigintOwnerRejected() {
        String table = createTableWithOwnerCandidate("acl_type", true);

        assertThatThrownBy(() -> aclService.putAcl(project, table, put(ALL_OFF, READ_ONLY, "title")))
            .isInstanceOf(BaasBadRequestException.class);
    }

    @Test
    void physicalOwnerDriftIsRejectedBeforeOwnershipOrIndexDdl() {
        String table = createTableWithOwnerCandidate("acl_drift", true);
        rootJdbc.execute("ALTER TABLE `" + project.getDbName() + "`.`" + table
                + "` MODIFY owner_id bigint unsigned NULL");
        AclPutDTO dto = put(ALL_OFF, READ_ONLY, "owner_id");

        assertThatThrownBy(() -> aclService.putAcl(project, table, dto))
            .isInstanceOf(DdlConflictException.class)
            .hasMessageContaining("owner 物理列");
        assertThat(ddlLogMapper.selectByProjectAndOperation(project.getId(), dto.operationId())).isNull();
        assertThat(indexCountOn(table, "owner_id")).isZero();
    }

    @Test
    void physicalOwnerNullabilityDriftRejectedEvenWithoutAnonInsert() {
        String table = createTableWithOwnerCandidate("acl_null_drift", true);
        rootJdbc.execute("ALTER TABLE `" + project.getDbName() + "`.`" + table
                + "` MODIFY owner_id bigint NOT NULL");
        AclPutDTO dto = put(ALL_OFF, READ_ONLY, "owner_id");

        assertThatThrownBy(() -> aclService.putAcl(project, table, dto))
            .isInstanceOf(DdlConflictException.class)
            .hasMessageContaining("owner 物理列");
        assertThat(ddlLogMapper.selectByProjectAndOperation(project.getId(), dto.operationId())).isNull();
        assertThat(indexCountOn(table, "owner_id")).isZero();
    }

    @Test
    void physicalOwnerTypeDriftFromBigintToIntIsConflict() {
        String table = createTableWithOwnerCandidate("acl_type_drift", true);
        rootJdbc.execute("ALTER TABLE `" + project.getDbName() + "`.`" + table
                + "` MODIFY owner_id int NULL");
        AclPutDTO dto = put(READ_ONLY, READ_ONLY, "owner_id");

        assertThatThrownBy(() -> aclService.putAcl(project, table, dto))
            .isInstanceOf(DdlConflictException.class)
            .hasMessageContaining("物理列");
        assertAclPutRejectedWithoutSideEffects(table, dto);
    }

    @Test
    void existingOrdinaryOwnerIndexCannotReplaceMetadataUniqueFlag() {
        String table = createTableWithOwnerIndex("acl_idx_u2i", true);
        rootJdbc.execute("ALTER TABLE `" + project.getDbName() + "`.`" + table
                + "` DROP INDEX `uk_owner_id`, ADD INDEX `idx_owner_id` (`owner_id`)");
        AclPutDTO dto = put(READ_ONLY, READ_ONLY, "owner_id");

        assertThatThrownBy(() -> aclService.putAcl(project, table, dto))
            .isInstanceOf(DdlConflictException.class)
            .hasMessageContaining("owner");

        assertAclPutRejectedWithoutSideEffects(table, dto);
        assertOwnerIndexFlags(table, true, false);
        assertThat(indexIsUnique(table, "owner_id")).isFalse();
    }

    @Test
    void existingUniqueOwnerIndexCannotReplaceMetadataIndexedFlag() {
        String table = createTableWithOwnerIndex("acl_idx_i2u", false);
        rootJdbc.execute("ALTER TABLE `" + project.getDbName() + "`.`" + table
                + "` DROP INDEX `idx_owner_id`, ADD UNIQUE INDEX `uk_owner_id` (`owner_id`)");
        AclPutDTO dto = put(READ_ONLY, READ_ONLY, "owner_id");

        assertThatThrownBy(() -> aclService.putAcl(project, table, dto))
            .isInstanceOf(DdlConflictException.class)
            .hasMessageContaining("owner");

        assertAclPutRejectedWithoutSideEffects(table, dto);
        assertOwnerIndexFlags(table, false, true);
        assertThat(indexIsUnique(table, "owner_id")).isTrue();
    }

    @Test
    void ownerConfigRejectsColumnCollationDriftWithoutSideEffects() {
        String table = createTableWithOwnerCandidate("acl_col_coll", true);
        rootJdbc.execute("ALTER TABLE `" + project.getDbName() + "`.`" + table
                + "` MODIFY `title` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL");
        AclPutDTO dto = put(READ_ONLY, READ_ONLY, "owner_id");

        assertThatThrownBy(() -> aclService.putAcl(project, table, dto))
            .isInstanceOf(DdlConflictException.class)
            .hasMessageContaining("基线");

        assertAclPutRejectedWithoutSideEffects(table, dto);
        assertThat(indexCountOn(table, "owner_id")).isZero();
    }

    @Test
    void ordinaryAndUniqueOwnerIndexesAreRejectedAsAmbiguous() {
        String table = createTableWithOwnerCandidate("acl_idx_mix", true);
        rootJdbc.execute("ALTER TABLE `" + project.getDbName() + "`.`" + table
                + "` ADD INDEX idx_owner_normal (owner_id), ADD UNIQUE INDEX uk_owner (owner_id)");
        AclPutDTO dto = put(READ_ONLY, READ_ONLY, "owner_id");

        assertThatThrownBy(() -> aclService.putAcl(project, table, dto))
            .isInstanceOf(DdlConflictException.class)
            .hasMessageContaining("索引");
        assertAclPutRejectedWithoutSideEffects(table, dto);
        assertThat(secondaryIndexCount(table)).isEqualTo(2L);
    }

    @Test
    void duplicateOrdinaryOwnerIndexesAreRejectedAsAmbiguous() {
        String table = createTableWithOwnerCandidate("acl_idx_dup", true);
        rootJdbc.execute("ALTER TABLE `" + project.getDbName() + "`.`" + table
                + "` ADD INDEX idx_owner_a (owner_id), ADD INDEX idx_owner_b (owner_id)");
        AclPutDTO dto = put(READ_ONLY, READ_ONLY, "owner_id");

        assertThatThrownBy(() -> aclService.putAcl(project, table, dto))
            .isInstanceOf(DdlConflictException.class)
            .hasMessageContaining("索引");
        assertAclPutRejectedWithoutSideEffects(table, dto);
        assertThat(secondaryIndexCount(table)).isEqualTo(2L);
    }

    @Test
    void compositeOwnerIndexIsRejectedInsteadOfAddingSingleColumnIndex() {
        String table = createTableWithOwnerCandidate("acl_idx_comp", true);
        rootJdbc.execute("ALTER TABLE `" + project.getDbName() + "`.`" + table
                + "` ADD INDEX idx_owner_title (owner_id, title)");
        AclPutDTO dto = put(READ_ONLY, READ_ONLY, "owner_id");

        assertThatThrownBy(() -> aclService.putAcl(project, table, dto))
            .isInstanceOf(DdlConflictException.class)
            .hasMessageContaining("索引");
        assertAclPutRejectedWithoutSideEffects(table, dto);
        assertThat(secondaryIndexCount(table)).isEqualTo(1L);
    }

    @Test
    void unrelatedPrefixIndexIsRejectedBeforeAddingOwnerIndex() {
        String table = createTableWithOwnerCandidate("acl_idx_prefix", true);
        rootJdbc.execute("ALTER TABLE `" + project.getDbName() + "`.`" + table
                + "` ADD INDEX idx_title_prefix (title(16))");
        AclPutDTO dto = put(READ_ONLY, READ_ONLY, "owner_id");

        assertThatThrownBy(() -> aclService.putAcl(project, table, dto))
            .isInstanceOf(DdlConflictException.class)
            .hasMessageContaining("索引");
        assertAclPutRejectedWithoutSideEffects(table, dto);
        assertThat(secondaryIndexCount(table)).isEqualTo(1L);
    }

    @Test
    void missingPhysicalOwnerColumnIsConflictWithoutLogOrAclChange() {
        String table = createTableWithOwnerCandidate("acl_missing_phy", true);
        rootJdbc.execute("ALTER TABLE `" + project.getDbName() + "`.`" + table + "` DROP COLUMN owner_id");
        AclPutDTO dto = put(READ_ONLY, READ_ONLY, "owner_id");

        assertThatThrownBy(() -> aclService.putAcl(project, table, dto))
            .isInstanceOf(DdlConflictException.class)
            .hasMessageContaining("物理列");
        assertThat(ddlLogMapper.selectByProjectAndOperation(project.getId(), dto.operationId())).isNull();
        assertThat(tableService.getTableSnapshot(project, table).at("/acl/anon/select").asBoolean()).isFalse();
    }

    @Test
    void ownerIndexAtMysqlTotalKeyLimitSucceeds() {
        List<ColumnDefinitionDTO> columns = new ArrayList<>();
        columns.add(new ColumnDefinitionDTO("owner_id", "bigint", null, null, true, null, false, false, null));
        for (int index = 0; index < 62; index++) {
            columns.add(new ColumnDefinitionDTO("indexed_" + index, "int", null, null, true, null,
                    false, true, null));
        }
        String table = "acl_idx64";
        tableService.createTable(project,
                new TableCreateDTO(UUID.randomUUID().toString(), table, null, columns));

        aclService.putAcl(project, table, put(ALL_OFF, READ_ONLY, "owner_id"));

        assertThat(indexCountOn(table, "owner_id")).isEqualTo(1L);
    }

    @Test
    void addingOwnerIndexBeyondMysqlTotalIndexLimitIsRejected() {
        List<ColumnDefinitionDTO> columns = new ArrayList<>();
        columns.add(new ColumnDefinitionDTO("owner_id", "bigint", null, null, true, null, false, false, null));
        for (int index = 0; index < 63; index++) {
            columns.add(new ColumnDefinitionDTO("indexed_" + index, "int", null, null, true, null,
                    false, true, null));
        }
        String table = "acl_idx65";
        tableService.createTable(project,
                new TableCreateDTO(UUID.randomUUID().toString(), table, null, columns));

        assertThatThrownBy(() -> aclService.putAcl(project, table, put(ALL_OFF, READ_ONLY, "owner_id")))
            .isInstanceOf(BaasBadRequestException.class)
            .hasMessageContaining("64");
        assertThat(indexCountOn(table, "owner_id")).isZero();
    }

    @Test
    void malformedOperationIdRejectedBeforeLogCreation() {
        String table = createTableWithOwnerCandidate("acl_uuid", true);
        AclPutDTO dto = new AclPutDTO("not-a-uuid", new AclConfigDTO(ALL_OFF, READ_ONLY), null);

        assertThatThrownBy(() -> aclService.putAcl(project, table, dto))
            .isInstanceOf(BaasBadRequestException.class)
            .hasMessageContaining("UUID");
        assertThat(ddlLogMapper.selectByProjectAndOperation(project.getId(), dto.operationId())).isNull();
    }

    @Test
    void anonInsertRequiresNullableOwner() {
        String table = createTableWithOwnerCandidate("acl_nn", false);
        AclRoleDTO anonInsert = new AclRoleDTO(false, true, false, false);

        assertThatThrownBy(() -> aclService.putAcl(project, table, put(anonInsert, ALL_OFF, "owner_id")))
            .isInstanceOf(BaasBadRequestException.class);
    }

    @Test
    void cancelOwnerFailClosedClosesAllAclAndKeepsIndex() {
        String table = createTableWithOwnerCandidate("acl_cancel", true);
        aclService.putAcl(project, table, put(READ_ONLY, READ_ONLY, "owner_id"));

        ObjectNode snapshot = aclService.putAcl(project, table, put(READ_ONLY, READ_ONLY, null));

        assertThat(snapshot.get("aclClosedByOwnerCancel").asBoolean()).isTrue();
        assertThat(snapshot.at("/acl/anon/select").asBoolean()).isFalse();
        assertThat(snapshot.at("/acl/authenticated/select").asBoolean()).isFalse();
        assertThat(indexCountOn(table, "owner_id")).isEqualTo(1L);
    }

    @Test
    void droppingOwnerClosesAclAndClearsOwnerBeforeFailingPhysicalDdl() {
        String table = createTableWithOwnerCandidate("acl_drop_fail", true);
        aclService.putAcl(project, table, put(READ_ONLY, READ_ONLY, "owner_id"));
        rootJdbc.update("INSERT INTO `" + project.getDbName() + "`.`" + table
                + "` (owner_id, title) VALUES (?, ?)", 1L, "title-too-long");
        ColumnDefinitionDTO narrowTitle = new ColumnDefinitionDTO("title", "varchar", 4, null, true, null,
                false, false, null);
        TableAlterDTO dropOwner = new TableAlterDTO(UUID.randomUUID().toString(), true, null, null, null,
                List.of("owner_id"), List.of(narrowTitle), null);

        assertThatThrownBy(() -> tableService.alterTable(project, table, dropOwner))
            .isInstanceOf(RuntimeException.class);
        ObjectNode snapshot = tableService.getTableSnapshot(project, table);
        assertThat(snapshot.get("ownerColumn").isNull()).isTrue();
        assertThat(snapshot.at("/acl/anon/select").asBoolean()).isFalse();
        assertThat(snapshot.at("/acl/authenticated/select").asBoolean()).isFalse();
    }

    @Test
    void externallyDroppedIndexRebuiltOnNextOwnerConfig() {
        String table = createTableWithOwnerCandidate("acl_rebuild", true);
        aclService.putAcl(project, table, put(ALL_OFF, READ_ONLY, "owner_id"));
        aclService.putAcl(project, table, put(ALL_OFF, ALL_OFF, null));
        rootJdbc.execute("ALTER TABLE `" + project.getDbName() + "`.`" + table + "` DROP INDEX idx_owner_id");
        assertThat(indexCountOn(table, "owner_id")).isZero();

        aclService.putAcl(project, table, put(ALL_OFF, READ_ONLY, "owner_id"));

        assertThat(indexCountOn(table, "owner_id")).isEqualTo(1L);
    }

    @Test
    void replayReturnsOldSnapshotWithoutOverwritingNewerConfig() {
        String table = createTableWithOwnerCandidate("acl_replay", true);
        AclPutDTO first = put(READ_ONLY, READ_ONLY, null);
        ObjectNode firstSnapshot = aclService.putAcl(project, table, first);
        aclService.putAcl(project, table, put(ALL_OFF, ALL_OFF, null));

        ObjectNode replayed = aclService.putAcl(project, table, first);

        assertThat(replayed).isEqualTo(firstSnapshot);
        assertThat(tableService.getTableSnapshot(project, table).at("/acl/anon/select").asBoolean()).isFalse();
    }

    @Test
    void failedOwnerIndexOperationRetriesFromPhysicalState() {
        String table = createTableWithOwnerCandidate("acl_retry", true);
        AclPutDTO dto = put(ALL_OFF, READ_ONLY, "owner_id");
        BaasDdlLog failed = seedAclLog(table, dto, DdlStep.PREPARED, DdlLogStatus.FAILED);
        setTableStatus(table, TableStatus.CONFLICT);

        ObjectNode snapshot = aclService.putAcl(project, table, dto);

        assertThat(snapshot.get("ownerColumn").asText()).isEqualTo("owner_id");
        assertThat(indexCountOn(table, "owner_id")).isEqualTo(1L);
        BaasDdlLog completed = ddlLogMapper.selectById(failed.getId());
        assertThat(completed.getRetryCount()).isEqualTo(1);
        assertThat(completed.getStatus()).isEqualTo(DdlLogStatus.SUCCESS.name());
        assertThat(completed.getStep()).isEqualTo(DdlStep.METADATA_APPLIED.name());
    }

    @Test
    void failedMetadataOnlyOperationCannotClearUnrelatedConflictState() {
        String table = createTableWithOwnerCandidate("acl_meta_retry", true);
        rootJdbc.execute("ALTER TABLE `" + project.getDbName() + "`.`" + table
                + "` ADD INDEX `idx_owner_id` (`owner_id`)");
        AclPutDTO dto = put(READ_ONLY, READ_ONLY, "owner_id");
        BaasDdlLog failed = seedAclLog(table, dto, DdlStep.PREPARED, DdlLogStatus.FAILED, null);
        setTableStatus(table, TableStatus.CONFLICT);

        assertThatThrownBy(() -> aclService.putAcl(project, table, dto))
            .isInstanceOf(DdlConflictException.class)
            .hasMessageContaining("状态");

        assertThat(tableService.findTableRow(project.getId(), table).getStatus())
            .isEqualTo(TableStatus.CONFLICT.name());
        assertThat(ddlLogMapper.selectById(failed.getId()).getStatus()).isEqualTo(DdlLogStatus.FAILED.name());
        assertThat(tableService.getTableSnapshot(project, table).at("/acl/anon/select").asBoolean()).isFalse();
    }

    @Test
    void staleOwnerIndexOperationTakesOverAppliedPhysicalTarget() {
        String table = createTableWithOwnerCandidate("acl_stale", true);
        AclPutDTO dto = put(ALL_OFF, READ_ONLY, "owner_id");
        rootJdbc.execute("ALTER TABLE `" + project.getDbName() + "`.`" + table
                + "` ADD INDEX `idx_owner_id` (`owner_id`)");
        BaasDdlLog stale = seedAclLog(table, dto, DdlStep.PREPARED, DdlLogStatus.RUNNING);
        setTableStatus(table, TableStatus.ALTERING);

        ObjectNode snapshot = aclService.putAcl(project, table, dto);

        assertThat(snapshot.get("ownerColumn").asText()).isEqualTo("owner_id");
        assertThat(indexCountOn(table, "owner_id")).isEqualTo(1L);
        BaasDdlLog completed = ddlLogMapper.selectById(stale.getId());
        assertThat(completed.getOwnerToken()).isNotEqualTo("dead-owner");
        assertThat(completed.getStatus()).isEqualTo(DdlLogStatus.SUCCESS.name());
        assertThat(completed.getStep()).isEqualTo(DdlStep.METADATA_APPLIED.name());
    }

    @Test
    void ownerIndexDeletedAfterInitialInspectionIsRejectedBeforeMetadataCommit() {
        String table = createTableWithOwnerIndex("acl_race_drop", false);
        BaasTable tableRow = tableService.findTableRow(project.getId(), table);
        AclPutDTO dto = put(READ_ONLY, READ_ONLY, "owner_id");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            rootJdbc.execute("ALTER TABLE `" + project.getDbName() + "`.`" + table
                    + "` DROP INDEX `idx_owner_id`");
            return null;
        }).when(tableService).patchDdlLog(anyLong(), eq(tableRow.getId()), isNull());

        try {
            assertThatThrownBy(() -> aclService.putAcl(project, table, dto))
                .isInstanceOf(DdlConflictException.class)
                .hasMessageContaining("owner");
        }
        finally {
            reset(tableService);
        }

        assertAclMetadataUnchanged(table, false, true);
        assertThat(indexCountOn(table, "owner_id")).isZero();
        assertFailedAclLog(dto);
    }

    @Test
    void ownerIndexReplacedAfterInitialInspectionIsRejectedBeforeMetadataCommit() {
        String table = createTableWithOwnerIndex("acl_race_repl", false);
        BaasTable tableRow = tableService.findTableRow(project.getId(), table);
        AclPutDTO dto = put(READ_ONLY, READ_ONLY, "owner_id");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            rootJdbc.execute("ALTER TABLE `" + project.getDbName() + "`.`" + table
                    + "` DROP INDEX `idx_owner_id`, ADD UNIQUE INDEX `uk_owner_id` (`owner_id`)");
            return null;
        }).when(tableService).patchDdlLog(anyLong(), eq(tableRow.getId()), isNull());

        try {
            assertThatThrownBy(() -> aclService.putAcl(project, table, dto))
                .isInstanceOf(DdlConflictException.class)
                .hasMessageContaining("owner");
        }
        finally {
            reset(tableService);
        }

        assertAclMetadataUnchanged(table, false, true);
        assertThat(indexIsUnique(table, "owner_id")).isTrue();
        assertFailedAclLog(dto);
    }

    @Test
    void getAclOmitsPutOnlyCancelFlag() {
        String table = createTableWithOwnerCandidate("acl_get", true);
        aclService.putAcl(project, table, put(READ_ONLY, READ_ONLY, null));

        ObjectNode snapshot = aclService.getAcl(project, table);

        assertThat(snapshot.has("aclClosedByOwnerCancel")).isFalse();
        assertThat(snapshot.at("/acl/anon/select").asBoolean()).isTrue();
    }

    private String createTableWithOwnerCandidate(String name, boolean ownerNullable) {
        tableService.createTable(project, new TableCreateDTO(UUID.randomUUID().toString(), name, null, List.of(
                new ColumnDefinitionDTO("owner_id", "bigint", null, null, ownerNullable, null, false, false, null),
                new ColumnDefinitionDTO("title", "varchar", 128, null, true, null, false, false, null))));
        return name;
    }

    private String createTableWithOwnerIndex(String name, boolean unique) {
        tableService.createTable(project, new TableCreateDTO(UUID.randomUUID().toString(), name, null, List.of(
                new ColumnDefinitionDTO("owner_id", "bigint", null, null, true, null, unique, !unique, null),
                new ColumnDefinitionDTO("title", "varchar", 128, null, true, null, false, false, null))));
        return name;
    }

    private AclPutDTO put(AclRoleDTO anon, AclRoleDTO authenticated, String ownerColumn) {
        return new AclPutDTO(UUID.randomUUID().toString(), new AclConfigDTO(anon, authenticated), ownerColumn);
    }

    private long indexCountOn(String table, String column) {
        return rootJdbc.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND COLUMN_NAME = ?", Long.class,
                project.getDbName(), table, column);
    }

    private long secondaryIndexCount(String table) {
        return rootJdbc.queryForObject("SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND INDEX_NAME <> 'PRIMARY'", Long.class,
                project.getDbName(), table);
    }

    private boolean indexIsUnique(String table, String column) {
        Integer nonUnique = rootJdbc.queryForObject("SELECT NON_UNIQUE FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND COLUMN_NAME = ? AND INDEX_NAME <> 'PRIMARY'",
                Integer.class, project.getDbName(), table, column);
        return nonUnique != null && nonUnique == 0;
    }

    private void assertOwnerIndexFlags(String table, boolean unique, boolean indexed) {
        JsonNode owner = tableService.getTableSnapshot(project, table)
            .withArray("columns")
            .findParents("columnName")
            .stream()
            .filter(node -> "owner_id".equals(node.get("columnName").asText()))
            .findFirst()
            .orElseThrow();
        assertThat(owner.get("unique").asBoolean()).isEqualTo(unique);
        assertThat(owner.get("indexed").asBoolean()).isEqualTo(indexed);
    }

    private void assertAclMetadataUnchanged(String table, boolean unique, boolean indexed) {
        ObjectNode snapshot = tableService.getTableSnapshot(project, table);
        assertThat(snapshot.get("ownerColumn").isNull()).isTrue();
        assertThat(snapshot.at("/acl/anon/select").asBoolean()).isFalse();
        assertThat(snapshot.at("/acl/authenticated/select").asBoolean()).isFalse();
        assertOwnerIndexFlags(table, unique, indexed);
        assertThat(snapshot.get("status").asText()).isEqualTo(TableStatus.ACTIVE.name());
    }

    private void assertFailedAclLog(AclPutDTO dto) {
        BaasDdlLog logRecord = ddlLogMapper.selectByProjectAndOperation(project.getId(), dto.operationId());
        assertThat(logRecord).isNotNull();
        assertThat(logRecord.getStatus()).isEqualTo(DdlLogStatus.FAILED.name());
        assertThat(logRecord.getStep()).isEqualTo(DdlStep.PREPARED.name());
    }

    private void assertAclPutRejectedWithoutSideEffects(String table, AclPutDTO dto) {
        assertThat(ddlLogMapper.selectByProjectAndOperation(project.getId(), dto.operationId())).isNull();
        ObjectNode snapshot = tableService.getTableSnapshot(project, table);
        assertThat(snapshot.get("ownerColumn").isNull()).isTrue();
        assertThat(snapshot.at("/acl/anon/select").asBoolean()).isFalse();
        assertThat(snapshot.at("/acl/authenticated/select").asBoolean()).isFalse();
    }

    private BaasDdlLog seedAclLog(String table, AclPutDTO dto, DdlStep step, DdlLogStatus status) {
        return seedAclLog(table, dto, step, status, "ALTER TABLE `" + project.getDbName() + "`.`" + table
                + "` ADD INDEX `idx_owner_id` (`owner_id`)");
    }

    private BaasDdlLog seedAclLog(String table, AclPutDTO dto, DdlStep step, DdlLogStatus status, String ddlText) {
        BaasTable tableRow = tableService.findTableRow(project.getId(), table);
        String path = "/studio/projects/" + project.getProjectRef() + "/tables/" + table + "/acl";
        BaasDdlLog logRecord = new BaasDdlLog();
        logRecord.setOperationId(dto.operationId());
        logRecord.setProjectId(project.getId());
        logRecord.setOperationType(DdlOperationType.ACL_CONFIG.code());
        logRecord.setTableName(table);
        logRecord.setTableId(tableRow.getId());
        logRecord.setRequestHash(RequestFingerprint.http("PUT", path, DdlOperationType.ACL_CONFIG.code(),
                RequestFingerprint.canonicalBody(dto)));
        logRecord.setOwnerToken("dead-owner");
        logRecord.setFenceEpoch(project.getDdlFenceEpoch());
        logRecord.setDdlText(ddlText);
        logRecord.setStep(step.name());
        logRecord.setStatus(status.name());
        logRecord.setRetryCount(0);
        ddlLogMapper.insert(logRecord);
        return logRecord;
    }

    private void setTableStatus(String table, TableStatus status) {
        BaasTable tableRow = tableService.findTableRow(project.getId(), table);
        tableRow.setStatus(status.name());
        rootJdbc.update("UPDATE baas_table SET status = ? WHERE id = ?", status.name(), tableRow.getId());
    }

}
