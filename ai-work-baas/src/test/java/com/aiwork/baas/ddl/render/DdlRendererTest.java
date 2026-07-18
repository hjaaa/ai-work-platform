package com.aiwork.baas.ddl.render;

import com.aiwork.baas.ddl.type.ColumnType;
import com.aiwork.baas.ddl.type.DefaultValueRenderer;
import com.aiwork.baas.ddl.type.LogicalColumn;
import com.aiwork.baas.exception.BaasBadRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DdlRendererTest {

    private DdlRenderer.ColumnPlan plan(String name, ColumnType type, Integer length, Integer scale,
            boolean nullable, DefaultValueRenderer.Rendered defaultValue, boolean unique, boolean indexed,
            String comment) {
        return new DdlRenderer.ColumnPlan(new LogicalColumn(name, type, length, scale, nullable,
                defaultValue == null ? null : defaultValue.canonical(), false, false, unique, indexed, comment),
                defaultValue);
    }

    @Test
    void createTableCarriesBaselineAutoIdAndIndexes() {
        var rendered = DdlRenderer.renderCreateTable("baas_p1", "orders", "订单表", List.of(
                plan("email", ColumnType.VARCHAR, 255, null, false, null, true, false, "邮箱"),
                plan("age", ColumnType.INT, null, null, true,
                        new DefaultValueRenderer.Rendered("18", "18"), false, true, null),
                plan("vip", ColumnType.BOOLEAN, null, null, true,
                        new DefaultValueRenderer.Rendered("TRUE", "true"), false, false, null)));

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
    void injectionPayloadsInCommentsAreEscapedNeverRaw() {
        String payload = "x', DROP TABLE t; --";
        var rendered = DdlRenderer.renderCreateTable("baas_p1", "t1", payload,
                List.of(plan("c1", ColumnType.INT, null, null, true, null, false, false, payload)));
        assertThat(rendered.sql()).doesNotContain("x', DROP TABLE");
        assertThat(rendered.sql()).contains("x'', DROP TABLE");
    }

    @Test
    void commentLengthLimitsEnforced() {
        assertThatThrownBy(() -> DdlRenderer.renderCreateTable("baas_p1", "t2", "长".repeat(2049),
                List.of(plan("c1", ColumnType.INT, null, null, true, null, false, false, null))))
            .isInstanceOf(BaasBadRequestException.class);
        assertThatThrownBy(() -> DdlRenderer.renderCreateTable("baas_p1", "t2", null,
                List.of(plan("c1", ColumnType.INT, null, null, true, null, false, false, "长".repeat(1025)))))
            .isInstanceOf(BaasBadRequestException.class);
    }

    @Test
    void alterTableJoinsClausesIntoSingleStatement() {
        var rendered = DdlRenderer.renderAlterTable("baas_p1", "orders", List.of(
                new DdlRenderer.AlterClause("ADD COLUMN `note` text NULL", "ADD COLUMN `note` text NULL"),
                new DdlRenderer.AlterClause("DROP COLUMN `age`", "DROP COLUMN `age`"),
                new DdlRenderer.AlterClause("COMMENT='内部注释'", "COMMENT=?"),
                new DdlRenderer.AlterClause("RENAME TO `baas_p1`.`orders2`", "RENAME TO `baas_p1`.`orders2`")));
        assertThat(rendered.sql()).isEqualTo("ALTER TABLE `baas_p1`.`orders` ADD COLUMN `note` text NULL, "
                + "DROP COLUMN `age`, COMMENT='内部注释', RENAME TO `baas_p1`.`orders2`");
        assertThat(rendered.sanitizedSql()).contains("COMMENT=?").doesNotContain("内部注释");
    }

    @Test
    void illegalIdentifiersRejectedDefenseInDepth() {
        assertThatThrownBy(() -> DdlRenderer.renderCreateTable("baas_p1", "select", null,
                List.of(plan("c1", ColumnType.INT, null, null, true, null, false, false, null))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DdlRenderer.renderCreateTable("baas_p1", "t_ok", null,
                List.of(plan("BadName", ColumnType.INT, null, null, true, null, false, false, null))))
            .isInstanceOf(IllegalArgumentException.class);
    }

}
