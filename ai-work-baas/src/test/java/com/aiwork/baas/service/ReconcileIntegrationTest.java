/*
 *
 *      Copyright (c) 2018-2025, lengleng All rights reserved.
 *
 */

package com.aiwork.baas.service;

import com.aiwork.baas.controller.dto.AclConfigDTO;
import com.aiwork.baas.controller.dto.AclPutDTO;
import com.aiwork.baas.controller.dto.AclRoleDTO;
import com.aiwork.baas.controller.dto.ColumnDefinitionDTO;
import com.aiwork.baas.controller.dto.ReconcileTriggerDTO;
import com.aiwork.baas.controller.dto.TableCreateDTO;
import com.aiwork.baas.entity.BaasTable;
import com.aiwork.baas.entity.enums.TableStatus;
import com.aiwork.baas.exception.BaasBadRequestException;
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

/**
 * 项目库表结构对账集成测试。
 *
 * @author ai-work
 * @date 2026/07/19
 */
class ReconcileIntegrationTest extends PlanBProjectIntegrationTestSupport {

    @Override
    protected String projectNamePrefix() {
        return "recon";
    }

    @Autowired
    private ReconcileService reconcileService;

    @Autowired
    private TableManagementService tableService;

    @Autowired
    private AclConfigService aclService;

    @Autowired
    private BaasTableMapper tableMapper;

    private ObjectNode reconcile() {
        return reconcileService.manualReconcile(project, new ReconcileTriggerDTO(UUID.randomUUID().toString()));
    }

    private String db() {
        return project.getDbName();
    }

    private String createManaged(String name) {
        tableService.createTable(project, new TableCreateDTO(UUID.randomUUID().toString(), name, null, List.of(
                new ColumnDefinitionDTO("name", "varchar", 64, null, true, null, false, false, null))));
        return name;
    }

    private String statusOf(String name) {
        return tableMapper.selectOne(Wrappers.<BaasTable>lambdaQuery()
            .eq(BaasTable::getProjectId, project.getId())
            .eq(BaasTable::getTableName, name)).getStatus();
    }

    @Test
    void missingPhysicalTableMarksConflict() {
        String table = createManaged("rc_missing");
        rootJdbc.execute("DROP TABLE `" + db() + "`.`" + table + "`");

        ObjectNode report = reconcile();

        assertThat(statusOf(table)).isEqualTo(TableStatus.CONFLICT.name());
        assertThat(report.get("conflicts").toString()).contains(table);
    }

    @Test
    void admissibleExternalTableImportedWithClosedAcl() {
        rootJdbc.execute("CREATE TABLE `" + db() + "`.rc_import (id bigint NOT NULL AUTO_INCREMENT, "
                + "email varchar(255) NOT NULL, PRIMARY KEY (id), UNIQUE KEY foo_email (email)) "
                + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC "
                + "COMMENT='外部表注释'");

        ObjectNode report = reconcile();

        assertThat(report.get("imported").toString()).contains("rc_import");
        ObjectNode snapshot = tableService.getTableSnapshot(project, "rc_import");
        assertThat(snapshot.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(snapshot.get("comment").asText()).isEqualTo("外部表注释");
        assertThat(snapshot.at("/acl/anon/select").asBoolean()).isFalse();
        assertThat(snapshot.get("columns").toString()).contains("\"unique\":true");
    }

    @Test
    void inadmissibleExternalTablesRejectedWithReasons() {
        rootJdbc.execute("CREATE TABLE `" + db() + "`.rc_myisam (id bigint NOT NULL AUTO_INCREMENT, "
                + "PRIMARY KEY (id)) ENGINE=MyISAM DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
        rootJdbc.execute("CREATE VIEW `" + db() + "`.rc_view AS SELECT 1 AS x");
        rootJdbc.execute("CREATE TABLE `" + db() + "`.rc_check (id bigint NOT NULL AUTO_INCREMENT, "
                + "n int CHECK (n > 0), PRIMARY KEY (id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 "
                + "COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC");
        rootJdbc.execute("CREATE TABLE `" + db() + "`.rc_unsigned (id bigint NOT NULL AUTO_INCREMENT, "
                + "n int unsigned, PRIMARY KEY (id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 "
                + "COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC");
        rootJdbc.execute("CREATE TABLE `" + db() + "`.rc_prefix (id bigint NOT NULL AUTO_INCREMENT, "
                + "name varchar(300), PRIMARY KEY (id), KEY idx_p (name(10))) ENGINE=InnoDB "
                + "DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC");
        rootJdbc.execute("CREATE TABLE `" + db() + "`.rc_onupd (id bigint NOT NULL AUTO_INCREMENT, "
                + "touched datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, PRIMARY KEY (id)) "
                + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC");

        ObjectNode report = reconcile();

        String rejected = report.get("rejectedImports").toString();
        for (String name : List.of("rc_myisam", "rc_view", "rc_check", "rc_unsigned", "rc_prefix", "rc_onupd")) {
            assertThat(rejected).contains(name);
            assertThat(tableMapper.selectCount(Wrappers.<BaasTable>lambdaQuery()
                .eq(BaasTable::getProjectId, project.getId())
                .eq(BaasTable::getTableName, name))).isZero();
        }
    }

    @Test
    void mappableDriftCorrectedFromDatabase() {
        String table = createManaged("rc_drift");
        rootJdbc.execute("ALTER TABLE `" + db() + "`.`" + table + "` ADD COLUMN extra_col int NULL, "
                + "MODIFY name varchar(128) NULL COMMENT 'changed', COMMENT='库侧新注释'");

        ObjectNode report = reconcile();

        assertThat(report.get("corrected").toString()).contains(table);
        ObjectNode snapshot = tableService.getTableSnapshot(project, table);
        assertThat(snapshot.get("columns").toString()).contains("extra_col").contains("\"length\":128");
        assertThat(snapshot.get("comment").asText()).isEqualTo("库侧新注释");
    }

    @Test
    void whitelistViolationMarksConflict() {
        String table = createManaged("rc_float");
        rootJdbc.execute("ALTER TABLE `" + db() + "`.`" + table + "` ADD COLUMN f float");

        reconcile();

        assertThat(statusOf(table)).isEqualTo(TableStatus.CONFLICT.name());
    }

    @Test
    void compositeIndexMarksConflictNotCompressed() {
        String table = createManaged("rc_comp");
        rootJdbc.execute("ALTER TABLE `" + db() + "`.`" + table + "` ADD COLUMN a int, ADD COLUMN b int, "
                + "ADD INDEX idx_ab (a, b)");

        reconcile();

        assertThat(statusOf(table)).isEqualTo(TableStatus.CONFLICT.name());
    }

    @Test
    void ownerConstraintBreakMarksConflictWithoutCorrection() {
        String table = "rc_owner";
        tableService.createTable(project, new TableCreateDTO(UUID.randomUUID().toString(), table, null, List.of(
                new ColumnDefinitionDTO("owner_id", "bigint", null, null, true, null, false, false, null))));
        aclService.putAcl(project, table, new AclPutDTO(UUID.randomUUID().toString(),
                new AclConfigDTO(new AclRoleDTO(false, false, false, false),
                        new AclRoleDTO(true, false, false, false)),
                "owner_id"));
        rootJdbc.execute("ALTER TABLE `" + db() + "`.`" + table + "` DROP INDEX idx_owner_id");

        reconcile();
        assertThat(statusOf(table)).isEqualTo(TableStatus.CONFLICT.name());

        rootJdbc.execute("ALTER TABLE `" + db() + "`.`" + table + "` ADD INDEX idx_owner_id (owner_id)");
        reconcile();
        assertThat(statusOf(table)).isEqualTo(TableStatus.ACTIVE.name());
    }

    @Test
    void tombstoneNeverResurrected() {
        String table = createManaged("rc_tomb");
        tableService.dropTable(project, table, UUID.randomUUID().toString());

        reconcile();

        assertThat(statusOf(table)).isEqualTo(TableStatus.DELETED.name());
    }

    @Test
    void noDriftForBooleanAndCurrentTimestampDefaults() {
        String table = "rc_stable";
        tableService.createTable(project, new TableCreateDTO(UUID.randomUUID().toString(), table, null, List.of(
                new ColumnDefinitionDTO("vip", "boolean", null, null, true,
                        MAPPER.getNodeFactory().booleanNode(true), false, false, null),
                new ColumnDefinitionDTO("created", "datetime", null, null, true,
                        MAPPER.getNodeFactory().textNode("CURRENT_TIMESTAMP"), false, false, null))));

        ObjectNode report = reconcile();

        assertThat(report.get("corrected").toString()).doesNotContain(table);
        assertThat(statusOf(table)).isEqualTo(TableStatus.ACTIVE.name());
    }

    @Test
    void replayReturnsOriginalReport() {
        createManaged("rc_replay");
        ReconcileTriggerDTO dto = new ReconcileTriggerDTO(UUID.randomUUID().toString());
        ObjectNode first = reconcileService.manualReconcile(project, dto);
        ObjectNode second = reconcileService.manualReconcile(project, dto);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void malformedManualOperationIdRejectedBeforeLogCreation() {
        ReconcileTriggerDTO dto = new ReconcileTriggerDTO("not-a-uuid");

        assertThatThrownBy(() -> reconcileService.manualReconcile(project, dto))
            .isInstanceOf(BaasBadRequestException.class)
            .hasMessageContaining("UUID");
    }

}
