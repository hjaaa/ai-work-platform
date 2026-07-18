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
import com.aiwork.baas.controller.dto.TableCreateDTO;
import com.aiwork.baas.ddl.RequestFingerprint;
import com.aiwork.baas.entity.BaasDdlLog;
import com.aiwork.baas.entity.BaasTable;
import com.aiwork.baas.entity.BaasTableAcl;
import com.aiwork.baas.entity.enums.DdlLogStatus;
import com.aiwork.baas.entity.enums.DdlOperationType;
import com.aiwork.baas.entity.enums.DdlStep;
import com.aiwork.baas.entity.enums.TableStatus;
import com.aiwork.baas.exception.BaasBadRequestException;
import com.aiwork.baas.exception.DdlConflictException;
import com.aiwork.baas.mapper.BaasDdlLogMapper;
import com.aiwork.baas.mapper.BaasTableAclMapper;
import com.aiwork.baas.mapper.BaasTableMapper;
import com.aiwork.baas.support.PlanBProjectIntegrationTestSupport;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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

class TableAlterIntegrationTest extends PlanBProjectIntegrationTestSupport {

    @Autowired
    private TableManagementService tableService;

    @Autowired
    private BaasDdlLogMapper ddlLogMapper;

    @Autowired
    private BaasTableMapper tableMapper;

    @Autowired
    private BaasTableAclMapper aclMapper;

    @Override
    protected String projectNamePrefix() {
        return "tbl-alter";
    }

    @Test
    void addColumnWithIndexAndCommentChangeInOneStatement() {
        String table = createSimpleTable("alt_add");
        TableAlterDTO dto = new TableAlterDTO(UUID.randomUUID().toString(), null, null, "新注释",
                List.of(new ColumnDefinitionDTO("email", "varchar", 255, null, false, null, true, false, "邮箱")),
                null, null, null);

        ObjectNode snapshot = tableService.alterTable(project, table, dto);

        assertThat(snapshot.get("status").asText()).isEqualTo(TableStatus.ACTIVE.name());
        assertThat(snapshot.get("comment").asText()).isEqualTo("新注释");
        var indexRow = rootJdbc.queryForMap("SELECT INDEX_NAME, NON_UNIQUE FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND COLUMN_NAME = 'email'",
                project.getDbName(), table);
        assertThat(indexRow.get("INDEX_NAME")).isEqualTo("uk_email");
        assertThat(indexRow.get("NON_UNIQUE").toString()).isEqualTo("0");
    }

    @Test
    void renameTableAndColumnTogetherRenamesCanonicalIndex() {
        String table = createSimpleTable("alt_ren");
        TableAlterDTO dto = new TableAlterDTO(UUID.randomUUID().toString(), null, "alt_ren2", null, null, null,
                null, List.of(new ColumnRenameDTO("age", "years")));

        tableService.alterTable(project, table, dto);

        assertThat(rootJdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'alt_ren2' AND COLUMN_NAME = 'years'", Long.class,
                project.getDbName())).isEqualTo(1L);
        assertThat(rootJdbc.queryForList("SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'alt_ren2' AND COLUMN_NAME = 'years'", String.class,
                project.getDbName())).containsExactly("idx_years");
        assertThat(tableService.getTableSnapshot(project, "alt_ren2").get("tableName").asText())
            .isEqualTo("alt_ren2");
    }

    @Test
    void dropColumnRequiresAllowLossy() {
        String table = createSimpleTable("alt_drop");
        TableAlterDTO withoutFlag = new TableAlterDTO(UUID.randomUUID().toString(), null, null, null, null,
                List.of("name"), null, null);

        assertThatThrownBy(() -> tableService.alterTable(project, table, withoutFlag))
            .isInstanceOf(BaasBadRequestException.class)
            .hasMessageContaining("allowLossy");

        TableAlterDTO withFlag = new TableAlterDTO(UUID.randomUUID().toString(), true, null, null, null,
                List.of("name"), null, null);
        tableService.alterTable(project, table, withFlag);
        assertThat(rootJdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND COLUMN_NAME = 'name'", Long.class,
                project.getDbName(), table)).isZero();
    }

    @Test
    void losslessModifyNeedsNoFlagAndLossyNeedsFlag() {
        String table = createSimpleTable("alt_mod");
        TableAlterDTO widen = new TableAlterDTO(UUID.randomUUID().toString(), null, null, null, null, null,
                List.of(new ColumnDefinitionDTO("age", "bigint", null, null, true, null, false, true, null)), null);
        tableService.alterTable(project, table, widen);

        TableAlterDTO shrink = new TableAlterDTO(UUID.randomUUID().toString(), null, null, null, null, null,
                List.of(new ColumnDefinitionDTO("name", "varchar", 8, null, true, null, false, false, null)), null);
        assertThatThrownBy(() -> tableService.alterTable(project, table, shrink))
            .isInstanceOf(BaasBadRequestException.class)
            .hasMessageContaining("allowLossy");
    }

    @Test
    void incompatibleAlterIsAtomicAndReturnsConflictWithoutTruncation() {
        String table = createSimpleTable("alt_strict");
        rootJdbc.update("INSERT INTO `" + project.getDbName() + "`.`" + table + "` (name, age) VALUES (?, ?)",
                "a-very-long-name-over-8", 1);
        String operationId = UUID.randomUUID().toString();
        TableAlterDTO shrink = new TableAlterDTO(operationId, true, null, "不应生效",
                List.of(new ColumnDefinitionDTO("extra", "int", null, null, true, null, false, false, null)), null,
                List.of(new ColumnDefinitionDTO("name", "varchar", 8, null, true, null, false, false, null)), null);

        assertThatThrownBy(() -> tableService.alterTable(project, table, shrink))
            .isInstanceOf(DdlConflictException.class)
            .hasMessage("DDL 与现有数据不兼容");
        assertThat(tableRow(table).getStatus()).isEqualTo(TableStatus.CONFLICT.name());
        assertThat(ddlLogMapper.selectByProjectAndOperation(project.getId(), operationId).getStatus())
            .isEqualTo(DdlLogStatus.FAILED.name());
        assertThat(rootJdbc.queryForObject("SELECT name FROM `" + project.getDbName() + "`.`" + table
                + "` LIMIT 1", String.class)).isEqualTo("a-very-long-name-over-8");
        assertThat(columnCount(table, "extra")).isZero();
        assertThat(tableComment(table)).isNotEqualTo("不应生效");

        rootJdbc.update("DELETE FROM `" + project.getDbName() + "`.`" + table + "`");
        tableService.alterTable(project, table, shrink);
        assertThat(tableRow(table).getStatus()).isEqualTo(TableStatus.ACTIVE.name());
    }

    @Test
    void notNullOnColumnWithNullRowsRejectedByStrictMode() {
        String table = createSimpleTable("alt_nn");
        rootJdbc.update("INSERT INTO `" + project.getDbName() + "`.`" + table + "` (name) VALUES (NULL)");
        TableAlterDTO toNotNull = new TableAlterDTO(UUID.randomUUID().toString(), true, null, null, null, null,
                List.of(new ColumnDefinitionDTO("name", "varchar", 64, null, false, null, false, false, null)),
                null);

        assertThatThrownBy(() -> tableService.alterTable(project, table, toNotNull))
            .isInstanceOf(DdlConflictException.class)
            .hasMessage("DDL 与现有数据不兼容");
    }

    @Test
    void commentDefaultAndIndexOnlyAlterEachExecutesPhysicalDdl() {
        String table = createSimpleTable("alt_attr");

        tableService.alterTable(project, table, new TableAlterDTO(UUID.randomUUID().toString(), null, null, null,
                null, null, List.of(new ColumnDefinitionDTO("name", "varchar", 64, null, true, null,
                        false, false, "姓名")), null));
        assertThat(columnAttribute(table, "name", "COLUMN_COMMENT")).isEqualTo("姓名");

        tableService.alterTable(project, table, new TableAlterDTO(UUID.randomUUID().toString(), null, null, null,
                null, null, List.of(new ColumnDefinitionDTO("name", "varchar", 64, null, true,
                        MAPPER.getNodeFactory().textNode("guest"), false, false, "姓名")), null));
        assertThat(columnAttribute(table, "name", "COLUMN_DEFAULT")).isEqualTo("guest");

        tableService.alterTable(project, table, new TableAlterDTO(UUID.randomUUID().toString(), null, null, null,
                null, null, List.of(new ColumnDefinitionDTO("name", "varchar", 64, null, true,
                        MAPPER.getNodeFactory().textNode("guest"), false, true, "姓名")), null));
        assertThat(secondaryIndexCount(table, "name")).isEqualTo(1L);
    }

    @Test
    void idColumnProtectedInEveryOperation() {
        String table = createSimpleTable("alt_id");
        List<TableAlterDTO> requests = List.of(
                new TableAlterDTO(UUID.randomUUID().toString(), null, null, null,
                        List.of(new ColumnDefinitionDTO("id", "bigint", null, null, false, null, false, false,
                                null)), null, null, null),
                new TableAlterDTO(UUID.randomUUID().toString(), true, null, null, null, List.of("id"), null, null),
                new TableAlterDTO(UUID.randomUUID().toString(), true, null, null, null, null,
                        List.of(new ColumnDefinitionDTO("id", "bigint", null, null, false, null, false, false,
                                null)), null),
                new TableAlterDTO(UUID.randomUUID().toString(), null, null, null, null, null, null,
                        List.of(new ColumnRenameDTO("id", "id2"))));

        for (TableAlterDTO request : requests) {
            assertThatThrownBy(() -> tableService.alterTable(project, table, request))
                .isInstanceOf(BaasBadRequestException.class);
        }
    }

    @Test
    void sameColumnInTwoOperationsRejected() {
        String table = createSimpleTable("alt_dupop");
        TableAlterDTO dto = new TableAlterDTO(UUID.randomUUID().toString(), true, null, null, null,
                List.of("name"), null, List.of(new ColumnRenameDTO("name", "name2")));

        assertThatThrownBy(() -> tableService.alterTable(project, table, dto))
            .isInstanceOf(BaasBadRequestException.class);
    }

    @Test
    void emptyRequestRejectedWithoutDdlLog() {
        String table = createSimpleTable("alt_empty");
        String operationId = UUID.randomUUID().toString();

        assertThatThrownBy(() -> tableService.alterTable(project, table, alter(operationId)))
            .isInstanceOf(BaasBadRequestException.class);
        assertThat(ddlLogMapper.selectByProjectAndOperation(project.getId(), operationId)).isNull();
    }

    @Test
    void uniqueReplacedByNormalIndexInSingleAlter() {
        String table = "alt_uq";
        tableService.createTable(project, new TableCreateDTO(UUID.randomUUID().toString(), table, null, List.of(
                new ColumnDefinitionDTO("email", "varchar", 255, null, true, null, true, false, null))));
        TableAlterDTO toNormal = new TableAlterDTO(UUID.randomUUID().toString(), null, null, null, null, null,
                List.of(new ColumnDefinitionDTO("email", "varchar", 255, null, true, null, false, true, null)),
                null);

        tableService.alterTable(project, table, toNormal);

        var row = rootJdbc.queryForMap("SELECT INDEX_NAME, NON_UNIQUE FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND COLUMN_NAME = 'email'", project.getDbName(),
                table);
        assertThat(row.get("NON_UNIQUE").toString()).isEqualTo("1");
        assertThat(row.get("INDEX_NAME")).isEqualTo("idx_email");
    }

    @Test
    void nonCanonicalIndexNameLocatedByActualName() {
        String table = createSimpleTable("alt_foo");
        rootJdbc.execute("ALTER TABLE `" + project.getDbName() + "`.`" + table
                + "` RENAME INDEX idx_age TO foo_age");
        TableAlterDTO disableIndex = new TableAlterDTO(UUID.randomUUID().toString(), null, null, null, null, null,
                List.of(new ColumnDefinitionDTO("age", "int", null, null, true, null, false, false, null)), null);

        tableService.alterTable(project, table, disableIndex);

        assertThat(secondaryIndexCount(table, "age")).isZero();
    }

    @Test
    void actualIndexNameWithBacktickIsSafelyQuoted() {
        String table = createSimpleTable("alt_tick");
        rootJdbc.execute("ALTER TABLE `" + project.getDbName() + "`.`" + table
                + "` RENAME INDEX idx_age TO `foo``age`");
        TableAlterDTO disableIndex = new TableAlterDTO(UUID.randomUUID().toString(), null, null, null, null, null,
                List.of(new ColumnDefinitionDTO("age", "int", null, null, true, null, false, false, null)), null);

        tableService.alterTable(project, table, disableIndex);

        assertThat(secondaryIndexCount(table, "age")).isZero();
    }

    @Test
    void uppercaseForeignIndexNameOccupiesCanonicalNameAcrossFullAlterChain() {
        String table = createSimpleTable("alt_index_case");
        tableService.alterTable(project, table, new TableAlterDTO(UUID.randomUUID().toString(), null, null, null,
                null, null, List.of(new ColumnDefinitionDTO("name", "varchar", 64, null, true, null, false, true,
                        null)), null));
        rootJdbc.execute("ALTER TABLE `" + project.getDbName() + "`.`" + table
                + "` RENAME INDEX `idx_name` TO `IDX_EMAIL`");
        TableAlterDTO dto = new TableAlterDTO(UUID.randomUUID().toString(), null, null, null,
                List.of(new ColumnDefinitionDTO("email", "varchar", 64, null, true, null, false, true, null)),
                null, null, null);

        tableService.alterTable(project, table, dto);

        assertThat(indexNames(table, "name")).containsExactly("IDX_EMAIL");
        List<String> emailIndexes = indexNames(table, "email");
        assertThat(emailIndexes).hasSize(1);
        assertThat(emailIndexes.get(0)).startsWith("idx_email_").isNotEqualToIgnoringCase("IDX_EMAIL");
        assertThat(ddlLog(dto).getDdlText()).contains("ADD INDEX `idx_email_");
    }

    @Test
    void ambiguousIndexesOnOneColumnAreRejectedBeforeDdl() {
        String table = createSimpleTable("alt_amb_idx");
        rootJdbc.execute("ALTER TABLE `" + project.getDbName() + "`.`" + table + "` ADD INDEX foo_age (`age`)");
        String operationId = UUID.randomUUID().toString();
        TableAlterDTO disableIndex = new TableAlterDTO(operationId, null, null, null, null, null,
                List.of(new ColumnDefinitionDTO("age", "int", null, null, true, null, false, false, null)), null);

        assertThatThrownBy(() -> tableService.alterTable(project, table, disableIndex))
            .isInstanceOf(DdlConflictException.class);
        assertThat(secondaryIndexCount(table, "age")).isEqualTo(2L);
        assertThat(ddlLogMapper.selectByProjectAndOperation(project.getId(), operationId)).isNull();
    }

    @Test
    void renameToExistingOrTombstoneNameRejected() {
        String tableA = createSimpleTable("alt_na");
        createSimpleTable("alt_nb");
        assertThatThrownBy(() -> tableService.alterTable(project, tableA, new TableAlterDTO(
                UUID.randomUUID().toString(), null, "alt_nb", null, null, null, null, null)))
            .isInstanceOf(DdlConflictException.class);

        tableMapper.update(null, Wrappers.<BaasTable>lambdaUpdate()
            .eq(BaasTable::getProjectId, project.getId())
            .eq(BaasTable::getTableName, "alt_nb")
            .set(BaasTable::getStatus, TableStatus.DELETED.name()));
        assertThatThrownBy(() -> tableService.alterTable(project, tableA, new TableAlterDTO(
                UUID.randomUUID().toString(), null, "alt_nb", null, null, null, null, null)))
            .isInstanceOf(DdlConflictException.class);
    }

    @Test
    void varcharCurrentTimestampFunctionDefaultRemainsRejectedWithoutLogging() {
        String table = createSimpleTable("alt_default");
        String operationId = UUID.randomUUID().toString();
        TableAlterDTO dto = new TableAlterDTO(operationId, null, null, null,
                List.of(new ColumnDefinitionDTO("token", "varchar", 64, null, true,
                        MAPPER.getNodeFactory().textNode("current_timestamp()"), false, false, null)),
                null, null, null);

        assertThatThrownBy(() -> tableService.alterTable(project, table, dto))
            .isInstanceOf(BaasBadRequestException.class)
            .hasMessageNotContaining("CURRENT_TIMESTAMP()");
        assertThat(ddlLogMapper.selectByProjectAndOperation(project.getId(), operationId)).isNull();
    }

    @Test
    void ownerRenameUpdatesMetadataAndKeepsIndexInvariant() {
        String table = createSimpleTable("alt_owner_ren");
        tableService.alterTable(project, table, new TableAlterDTO(UUID.randomUUID().toString(), null, null, null,
                null, null, List.of(new ColumnDefinitionDTO("age", "bigint", null, null, true, null, false, true,
                        null)), null));
        seedOwner(table, "age", true);
        TableAlterDTO dto = new TableAlterDTO(UUID.randomUUID().toString(), null, null, null, null, null, null,
                List.of(new ColumnRenameDTO("age", "user_id")));

        tableService.alterTable(project, table, dto);

        assertThat(tableRow(table).getOwnerColumn()).isEqualTo("user_id");
        assertThat(secondaryIndexCount(table, "user_id")).isEqualTo(1L);
    }

    @Test
    void ownerDropFailureThenRetryPersistsAclClosureIntent() throws Exception {
        String table = createSimpleTable("alt_owner_drop");
        tableService.alterTable(project, table, new TableAlterDTO(UUID.randomUUID().toString(), null, null, null,
                null, null, List.of(new ColumnDefinitionDTO("age", "bigint", null, null, true, null, false, true,
                        null)), null));
        seedOwner(table, "age", true);
        rootJdbc.update("INSERT INTO `" + project.getDbName() + "`.`" + table + "` (name, age) VALUES (?, ?)",
                "a-very-long-name-over-8", 1);
        TableAlterDTO dto = new TableAlterDTO(UUID.randomUUID().toString(), true, null, null, null, List.of("age"),
                List.of(new ColumnDefinitionDTO("name", "varchar", 8, null, true, null, false, false, null)), null);

        assertThatThrownBy(() -> tableService.alterTable(project, table, dto))
            .isInstanceOf(DdlConflictException.class);

        BaasTable row = tableRow(table);
        assertThat(row.getOwnerColumn()).isNull();
        assertThat(row.getStatus()).isEqualTo(TableStatus.CONFLICT.name());
        assertThat(aclRows(row.getId())).allSatisfy(acl -> {
            assertThat(acl.getCanSelect()).isFalse();
            assertThat(acl.getCanInsert()).isFalse();
            assertThat(acl.getCanUpdate()).isFalse();
            assertThat(acl.getCanDelete()).isFalse();
        });
        assertThat(columnCount(table, "age")).isEqualTo(1L);
        assertThat(ddlLog(dto).getDdlText()).contains("ACL_CLOSED_BY_OWNER_DROP");

        rootJdbc.update("DELETE FROM `" + project.getDbName() + "`.`" + table + "`");
        ObjectNode success = tableService.alterTable(project, table, dto);
        ObjectNode replay = tableService.alterTable(project, table, dto);
        BaasDdlLog completedLog = ddlLog(dto);

        assertThat(success.path("aclClosedByOwnerDrop").asBoolean()).isTrue();
        assertThat(MAPPER.readTree(completedLog.getResultSnapshot()).path("aclClosedByOwnerDrop").asBoolean())
            .isTrue();
        assertThat(replay).isEqualTo(success);
        assertThat(replay.path("aclClosedByOwnerDrop").asBoolean()).isTrue();
        assertThat(tableRow(table).getOwnerColumn()).isNull();
        assertThat(aclRows(row.getId())).allSatisfy(acl -> {
            assertThat(acl.getCanSelect()).isFalse();
            assertThat(acl.getCanInsert()).isFalse();
            assertThat(acl.getCanUpdate()).isFalse();
            assertThat(acl.getCanDelete()).isFalse();
        });
    }

    @Test
    void ownerModifyCannotRemoveBigintOrIndexInvariant() {
        String table = createSimpleTable("alt_owner_mod");
        tableService.alterTable(project, table, new TableAlterDTO(UUID.randomUUID().toString(), null, null, null,
                null, null, List.of(new ColumnDefinitionDTO("age", "bigint", null, null, true, null, false, true,
                        null)), null));
        seedOwner(table, "age", true);

        TableAlterDTO wrongType = new TableAlterDTO(UUID.randomUUID().toString(), true, null, null, null, null,
                List.of(new ColumnDefinitionDTO("age", "int", null, null, true, null, false, true, null)), null);
        assertThatThrownBy(() -> tableService.alterTable(project, table, wrongType))
            .isInstanceOf(BaasBadRequestException.class);

        TableAlterDTO noIndex = new TableAlterDTO(UUID.randomUUID().toString(), null, null, null, null, null,
                List.of(new ColumnDefinitionDTO("age", "bigint", null, null, true, null, false, false, null)), null);
        assertThatThrownBy(() -> tableService.alterTable(project, table, noIndex))
            .isInstanceOf(BaasBadRequestException.class);
    }

    @Test
    void preparedRetryWithExactPhysicalTargetSkipsDuplicateDdlAndRepairsMetadata() {
        String table = createSimpleTable("alt_resume");
        TableAlterDTO dto = new TableAlterDTO(UUID.randomUUID().toString(), null, null, "恢复后注释",
                List.of(new ColumnDefinitionDTO("score", "int", null, null, true, null, false, true, null)),
                null, null, null);
        rootJdbc.execute("ALTER TABLE `" + project.getDbName() + "`.`" + table
                + "` ADD COLUMN `score` int NULL, ADD INDEX `imported_score` (`score`), COMMENT='恢复后注释'");
        seedAlterLog(table, dto, DdlStep.PREPARED, DdlLogStatus.FAILED);

        ObjectNode snapshot = tableService.alterTable(project, table, dto);

        assertThat(snapshot.get("status").asText()).isEqualTo(TableStatus.ACTIVE.name());
        assertThat(snapshot.get("comment").asText()).isEqualTo("恢复后注释");
        assertThat(columnCount(table, "score")).isEqualTo(1L);
        assertThat(ddlLog(dto).getStatus()).isEqualTo(DdlLogStatus.SUCCESS.name());
        assertThat(ddlLog(dto).getStep()).isEqualTo(DdlStep.METADATA_APPLIED.name());
    }

    @Test
    void preparedRetryAfterTableAndColumnRenameAcceptsExactTargetWithImportedIndexName() {
        String table = createSimpleTable("alt_resume_ren");
        String targetTable = "alt_resumed";
        TableAlterDTO dto = new TableAlterDTO(UUID.randomUUID().toString(), null, targetTable, null, null, null,
                null, List.of(new ColumnRenameDTO("age", "years")));
        rootJdbc.execute("ALTER TABLE `" + project.getDbName() + "`.`" + table
                + "` RENAME COLUMN `age` TO `years`, RENAME INDEX `idx_age` TO `imported_years`, RENAME TO `"
                + project.getDbName() + "`.`" + targetTable + "`");
        seedAlterLog(table, dto, DdlStep.PREPARED, DdlLogStatus.FAILED);

        ObjectNode snapshot = tableService.alterTable(project, table, dto);

        assertThat(snapshot.get("tableName").asText()).isEqualTo(targetTable);
        assertThat(columnCount(targetTable, "years")).isEqualTo(1L);
        assertThat(indexNames(targetTable, "years")).containsExactly("imported_years");
        assertThat(tableRow(targetTable).getStatus()).isEqualTo(TableStatus.ACTIVE.name());
        assertThat(ddlLog(dto).getStep()).isEqualTo(DdlStep.METADATA_APPLIED.name());
    }

    @Test
    void ddlAppliedRetryWithExactPhysicalTargetCompletesMetadata() {
        String table = createSimpleTable("alt_checkpoint_ok");
        TableAlterDTO dto = new TableAlterDTO(UUID.randomUUID().toString(), null, null, "已执行", null, null,
                null, null);
        rootJdbc.execute("ALTER TABLE `" + project.getDbName() + "`.`" + table + "` COMMENT='已执行'");
        seedAlterLog(table, dto, DdlStep.DDL_APPLIED, DdlLogStatus.FAILED);

        ObjectNode snapshot = tableService.alterTable(project, table, dto);

        assertThat(snapshot.get("comment").asText()).isEqualTo("已执行");
        assertThat(tableRow(table).getStatus()).isEqualTo(TableStatus.ACTIVE.name());
        assertThat(ddlLog(dto).getStatus()).isEqualTo(DdlLogStatus.SUCCESS.name());
        assertThat(ddlLog(dto).getStep()).isEqualTo(DdlStep.METADATA_APPLIED.name());
    }

    @Test
    void ddlAppliedRetryRejectsPhysicalSourceStateInsteadOfTrustingCheckpoint() {
        String table = createSimpleTable("alt_bad_checkpoint");
        TableAlterDTO dto = new TableAlterDTO(UUID.randomUUID().toString(), null, null, "未执行", null, null, null,
                null);
        seedAlterLog(table, dto, DdlStep.DDL_APPLIED, DdlLogStatus.FAILED);

        assertThatThrownBy(() -> tableService.alterTable(project, table, dto))
            .isInstanceOf(DdlConflictException.class)
            .hasMessageContaining("物理");

        assertThat(tableComment(table)).isNotEqualTo("未执行");
        assertThat(tableRow(table).getComment()).isNotEqualTo("未执行");
        assertThat(ddlLog(dto).getStep()).isEqualTo(DdlStep.DDL_APPLIED.name());
    }

    @Test
    void staleRunningWithExactPhysicalTargetIsTakenOver() {
        String table = createSimpleTable("alt_takeover");
        TableAlterDTO dto = new TableAlterDTO(UUID.randomUUID().toString(), null, null, "已执行", null, null, null,
                null);
        rootJdbc.execute("ALTER TABLE `" + project.getDbName() + "`.`" + table + "` COMMENT='已执行'");
        seedAlterLog(table, dto, DdlStep.PREPARED, DdlLogStatus.RUNNING);

        tableService.alterTable(project, table, dto);

        assertThat(ddlLog(dto).getStatus()).isEqualTo(DdlLogStatus.SUCCESS.name());
        assertThat(ddlLog(dto).getOwnerToken()).isNotEqualTo("dead-executor");
        assertThat(tableRow(table).getStatus()).isEqualTo(TableStatus.ACTIVE.name());
    }

    @Test
    void staleOwnerDropAfterOwnershipCrashRestoresPersistedAclClosureIntent() {
        String table = createSimpleTable("alt_owner_crash");
        tableService.alterTable(project, table, new TableAlterDTO(UUID.randomUUID().toString(), null, null, null,
                null, null, List.of(new ColumnDefinitionDTO("age", "bigint", null, null, true, null, false, true,
                        null)), null));
        seedOwner(table, "age", true);
        TableAlterDTO dto = new TableAlterDTO(UUID.randomUUID().toString(), true, null, null, null, List.of("age"),
                null, null);
        seedAlterLog(table, dto, DdlStep.PREPARED, DdlLogStatus.RUNNING);
        BaasTable row = tableRow(table);
        row.setOwnerColumn(null);
        row.setStatus(TableStatus.ALTERING.name());
        tableMapper.updateById(row);
        aclMapper.update(null, Wrappers.<BaasTableAcl>lambdaUpdate()
            .eq(BaasTableAcl::getTableId, row.getId())
            .set(BaasTableAcl::getCanSelect, false)
            .set(BaasTableAcl::getCanInsert, false)
            .set(BaasTableAcl::getCanUpdate, false)
            .set(BaasTableAcl::getCanDelete, false));
        BaasDdlLog crashedLog = ddlLog(dto);
        crashedLog.setDdlText("/* BAAS_INTENT:ACL_CLOSED_BY_OWNER_DROP */ ALTER TABLE `"
                + project.getDbName() + "`.`" + table + "` DROP COLUMN `age`");
        ddlLogMapper.updateById(crashedLog);

        ObjectNode success = tableService.alterTable(project, table, dto);

        assertThat(success.path("aclClosedByOwnerDrop").asBoolean()).isTrue();
        assertThat(columnCount(table, "age")).isZero();
        assertThat(tableRow(table).getOwnerColumn()).isNull();
        assertThat(aclRows(row.getId())).allSatisfy(acl -> {
            assertThat(acl.getCanSelect()).isFalse();
            assertThat(acl.getCanInsert()).isFalse();
            assertThat(acl.getCanUpdate()).isFalse();
            assertThat(acl.getCanDelete()).isFalse();
        });
    }

    @Test
    void concurrentDuplicateAlterHasExactlyOneWinner() throws Exception {
        String table = createSimpleTable("alt_concurrent");
        TableAlterDTO first = addScoreDto();
        TableAlterDTO second = addScoreDto();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Throwable> results = new ArrayList<>(2);
        try {
            Future<Throwable> firstResult = executor.submit(() -> runConcurrentAlter(table, first, ready, start));
            Future<Throwable> secondResult = executor.submit(() -> runConcurrentAlter(table, second, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            results.add(firstResult.get(30, TimeUnit.SECONDS));
            results.add(secondResult.get(30, TimeUnit.SECONDS));
        }
        finally {
            executor.shutdownNow();
        }

        assertThat(results.stream().filter(item -> item == null).count()).isEqualTo(1L);
        assertThat(results.stream().filter(item -> item instanceof RuntimeException).count()).isEqualTo(1L);
        assertThat(columnCount(table, "score")).isEqualTo(1L);
        assertThat(tableRow(table).getStatus()).isEqualTo(TableStatus.ACTIVE.name());
    }

    private String createSimpleTable(String name) {
        tableService.createTable(project, new TableCreateDTO(UUID.randomUUID().toString(), name, null, List.of(
                new ColumnDefinitionDTO("name", "varchar", 64, null, true, null, false, false, null),
                new ColumnDefinitionDTO("age", "int", null, null, true, null, false, true, null))));
        return name;
    }

    private TableAlterDTO alter(String operationId) {
        return new TableAlterDTO(operationId, null, null, null, null, null, null, null);
    }

    private TableAlterDTO addScoreDto() {
        return new TableAlterDTO(UUID.randomUUID().toString(), null, null, null,
                List.of(new ColumnDefinitionDTO("score", "int", null, null, true, null, false, false, null)),
                null, null, null);
    }

    private Throwable runConcurrentAlter(String table, TableAlterDTO dto, CountDownLatch ready,
            CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                return new AssertionError("concurrent alter start timeout");
            }
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return exception;
        }
        return catchThrowable(() -> tableService.alterTable(project, table, dto));
    }

    private void seedOwner(String table, String ownerColumn, boolean enableAcl) {
        BaasTable row = tableRow(table);
        row.setOwnerColumn(ownerColumn);
        tableMapper.updateById(row);
        if (enableAcl) {
            aclMapper.update(null, Wrappers.<BaasTableAcl>lambdaUpdate()
                .eq(BaasTableAcl::getTableId, row.getId())
                .set(BaasTableAcl::getCanSelect, true)
                .set(BaasTableAcl::getCanInsert, true)
                .set(BaasTableAcl::getCanUpdate, true)
                .set(BaasTableAcl::getCanDelete, true));
        }
    }

    private void seedAlterLog(String table, TableAlterDTO dto, DdlStep step, DdlLogStatus status) {
        BaasTable row = tableRow(table);
        row.setStatus(TableStatus.CONFLICT.name());
        tableMapper.updateById(row);
        BaasDdlLog log = new BaasDdlLog();
        log.setProjectId(project.getId());
        log.setOperationId(dto.operationId());
        log.setOperationType(DdlOperationType.ALTER.code());
        log.setTableName(table);
        log.setTableId(row.getId());
        log.setRequestHash(RequestFingerprint.http("PATCH",
                "/studio/projects/" + project.getProjectRef() + "/tables/" + table,
                DdlOperationType.ALTER.code(), RequestFingerprint.canonicalBody(dto)));
        log.setOwnerToken("dead-executor");
        log.setFenceEpoch(0L);
        log.setStep(step.name());
        log.setStatus(status.name());
        log.setRetryCount(0);
        ddlLogMapper.insert(log);
    }

    private BaasDdlLog ddlLog(TableAlterDTO dto) {
        return ddlLogMapper.selectByProjectAndOperation(project.getId(), dto.operationId());
    }

    private BaasTable tableRow(String table) {
        return tableMapper.selectOne(Wrappers.<BaasTable>lambdaQuery()
            .eq(BaasTable::getProjectId, project.getId())
            .eq(BaasTable::getTableName, table));
    }

    private List<BaasTableAcl> aclRows(Long tableId) {
        return aclMapper.selectList(Wrappers.<BaasTableAcl>lambdaQuery().eq(BaasTableAcl::getTableId, tableId));
    }

    private Long columnCount(String table, String column) {
        return rootJdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND COLUMN_NAME = ?", Long.class,
                project.getDbName(), table, column);
    }

    private Object columnAttribute(String table, String column, String attribute) {
        return rootJdbc.queryForObject("SELECT " + attribute + " FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND COLUMN_NAME = ?", Object.class,
                project.getDbName(), table, column);
    }

    private Long secondaryIndexCount(String table, String column) {
        return rootJdbc.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND COLUMN_NAME = ? AND INDEX_NAME <> 'PRIMARY'",
                Long.class, project.getDbName(), table, column);
    }

    private List<String> indexNames(String table, String column) {
        return rootJdbc.queryForList("SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND COLUMN_NAME = ? AND INDEX_NAME <> 'PRIMARY' "
                + "ORDER BY INDEX_NAME", String.class, project.getDbName(), table, column);
    }

    private String tableComment(String table) {
        return rootJdbc.queryForObject("SELECT TABLE_COMMENT FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?", String.class, project.getDbName(), table);
    }

}
