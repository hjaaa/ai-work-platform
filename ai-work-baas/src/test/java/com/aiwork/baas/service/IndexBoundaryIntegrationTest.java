package com.aiwork.baas.service;

import com.aiwork.baas.controller.dto.ColumnDefinitionDTO;
import com.aiwork.baas.controller.dto.TableAlterDTO;
import com.aiwork.baas.controller.dto.TableCreateDTO;
import com.aiwork.baas.ddl.lock.AdvisoryLockTemplate;
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
    private BaasDdlLogMapper ddlLogMapper;

    @Autowired
    private AdvisoryLockTemplate advisoryLockTemplate;

    @Override
    protected String projectNamePrefix() {
        return "idxb";
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

        assertThatThrownBy(() -> tableService.alterTable(project, "idx_wide",
                new TableAlterDTO(UUID.randomUUID().toString(), null, null, null, null, null,
                        List.of(new ColumnDefinitionDTO("v", "varchar", 800, null, true, null, false, true, null)),
                        null)))
                .isInstanceOf(BaasBadRequestException.class);
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

}
