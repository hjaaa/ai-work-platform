package com.aiwork.baas.ddl.render;

import com.aiwork.baas.ddl.type.ColumnType;
import com.aiwork.baas.ddl.type.LogicalColumn;
import com.aiwork.baas.exception.BaasBadRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DdlRendererTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DdlRenderer.ColumnPlan plan(String name, ColumnType type, Integer length, Integer scale,
            boolean nullable, String canonicalDefault, JsonNode rawDefault, boolean unique, boolean indexed,
            String comment) {
        return DdlRenderer.columnPlan(new LogicalColumn(name, type, length, scale, nullable, canonicalDefault, false,
                false, unique, indexed, comment), rawDefault);
    }

    @Test
    void createTableCarriesBaselineAutoIdAndIndexes() {
        var rendered = DdlRenderer.renderCreateTable("baas_p1", "orders", "订单表", List.of(
                plan("email", ColumnType.VARCHAR, 255, null, false, null, null, true, false, "邮箱"),
                plan("age", ColumnType.INT, null, null, true, "18", MAPPER.getNodeFactory().numberNode(18), false,
                        true, null),
                plan("vip", ColumnType.BOOLEAN, null, null, true, "true", MAPPER.getNodeFactory().booleanNode(true),
                        false, false, null)));

        assertThat(rendered.sql())
            .startsWith("CREATE TABLE `baas_p1`.`orders` (")
            .contains("`id` bigint NOT NULL AUTO_INCREMENT")
            .contains("`email` varchar(255) NOT NULL COMMENT '邮箱'")
            .contains("`age` int NULL DEFAULT 18")
            .contains("`vip` TINYINT(1) NULL DEFAULT TRUE")
            .contains("PRIMARY KEY (`id`)")
            .contains("UNIQUE KEY `uk_email` (`email`)")
            .contains("KEY `idx_age` (`age`)")
            .endsWith("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC "
                    + "COMMENT='订单表'");
        assertThat(rendered.sanitizedSql()).contains("`age` int NULL DEFAULT ?")
            .contains("`email` varchar(255) NOT NULL COMMENT ?")
            .endsWith("COMMENT=?")
            .doesNotContain("DEFAULT 18", "订单表", "邮箱");
    }

    @Test
    void columnPlanUsesDefaultRendererAndRejectsMismatchOrInvalidTypeParams() {
        assertThatThrownBy(() -> plan("title", ColumnType.VARCHAR, 0, null, true, null, null, false, false, null))
            .isInstanceOf(BaasBadRequestException.class);
        assertThatThrownBy(() -> plan("amount", ColumnType.DECIMAL, 66, 0, true, null, null, false, false, null))
            .isInstanceOf(BaasBadRequestException.class);
        assertThatThrownBy(() -> plan("age", ColumnType.INT, null, null, true, "18",
                MAPPER.getNodeFactory().numberNode(19), false, false, null))
            .isInstanceOf(BaasBadRequestException.class);
    }

    @Test
    void currentTimestampFunctionSyntaxIsRejectedForVarcharAndDatetime() {
        assertThatThrownBy(() -> plan("title", ColumnType.VARCHAR, 32, null, true, null,
                MAPPER.getNodeFactory().textNode("CURRENT_TIMESTAMP()"), false, false, null))
            .isInstanceOf(BaasBadRequestException.class);
        assertThatThrownBy(() -> plan("created_at", ColumnType.DATETIME, null, null, true, null,
                MAPPER.getNodeFactory().textNode("CURRENT_TIMESTAMP()"), false, false, null))
            .isInstanceOf(BaasBadRequestException.class);
        assertThat(plan("created_at", ColumnType.DATETIME, null, null, true, "CURRENT_TIMESTAMP",
                MAPPER.getNodeFactory().textNode("current_timestamp"), false, false, null).defaultValueSql())
            .isEqualTo("CURRENT_TIMESTAMP");
    }

    @Test
    void injectionPayloadsInCommentsAreEscapedNeverRaw() {
        String payload = "x', DROP TABLE t; --";
        var rendered = DdlRenderer.renderCreateTable("baas_p1", "t1", payload,
                List.of(plan("c1", ColumnType.INT, null, null, true, null, null, false, false, payload)));
        assertThat(rendered.sql()).doesNotContain("x', DROP TABLE");
        assertThat(rendered.sql()).contains("x'', DROP TABLE");
    }

    @Test
    void commentLengthLimitsUseCodePointCount() {
        assertThatCode(() -> DdlRenderer.renderCreateTable("baas_p1", "t2", "😀".repeat(2048),
                List.of(plan("c1", ColumnType.INT, null, null, true, null, null, false, false, "😀".repeat(1024)))))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> DdlRenderer.renderCreateTable("baas_p1", "t2", "😀".repeat(2049),
                List.of(plan("c1", ColumnType.INT, null, null, true, null, null, false, false, null))))
            .isInstanceOf(BaasBadRequestException.class);
        assertThatThrownBy(() -> DdlRenderer.renderCreateTable("baas_p1", "t2", null,
                List.of(plan("c1", ColumnType.INT, null, null, true, null, null, false, false, "😀".repeat(1025)))))
            .isInstanceOf(BaasBadRequestException.class);
    }

    @Test
    void alterTableJoinsTypedClausesIntoSingleSafeStatement() {
        var rendered = DdlRenderer.renderAlterTable("baas_p1", "orders", List.of(
                DdlRenderer.AlterClause.addColumn(
                        plan("note", ColumnType.TEXT, null, null, true, null, null, false, false, null)),
                DdlRenderer.AlterClause.dropColumn("age"),
                DdlRenderer.AlterClause.modifyColumn(
                        plan("title", ColumnType.VARCHAR, 64, null, true, "x", MAPPER.getNodeFactory().textNode("x"),
                                false, false, "标题")),
                DdlRenderer.AlterClause.renameColumn("note", "memo"),
                DdlRenderer.AlterClause.addIndex(false, "idx_memo", "memo"),
                DdlRenderer.AlterClause.renameIndex("idx_memo", "idx_note"),
                DdlRenderer.AlterClause.dropIndex("idx_note"),
                DdlRenderer.AlterClause.tableComment("内部'注释"),
                DdlRenderer.AlterClause.renameTable("baas_p1", "orders2")));
        assertThat(rendered.sql()).isEqualTo("ALTER TABLE `baas_p1`.`orders` ADD COLUMN `note` text NULL, "
                + "DROP COLUMN `age`, MODIFY COLUMN `title` varchar(64) NULL DEFAULT 'x' COMMENT '标题', "
                + "RENAME COLUMN `note` TO `memo`, ADD INDEX `idx_memo` (`memo`), "
                + "RENAME INDEX `idx_memo` TO `idx_note`, DROP INDEX `idx_note`, COMMENT='内部''注释', "
                + "RENAME TO `baas_p1`.`orders2`");
        assertThat(rendered.sanitizedSql()).contains("DEFAULT ?", "COMMENT ?", "COMMENT=?")
            .doesNotContain("内部", "标题", "'x'");
    }

    @Test
    void typedClausesValidateIdentifiersAndEscapeStrings() {
        assertThatThrownBy(() -> DdlRenderer.AlterClause.dropColumn("bad-name"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DdlRenderer.AlterClause.addIndex(true, "uk_email", "BadName"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void illegalIdentifiersRejectedDefenseInDepth() {
        assertThatThrownBy(() -> DdlRenderer.renderCreateTable("baas_p1", "select", null,
                List.of(plan("c1", ColumnType.INT, null, null, true, null, null, false, false, null))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DdlRenderer.renderCreateTable("baas_p1", "t_ok", null,
                List.of(plan("BadName", ColumnType.INT, null, null, true, null, null, false, false, null))))
            .isInstanceOf(IllegalArgumentException.class);
    }

}
