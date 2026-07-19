/*
 *
 *      Copyright (c) 2018-2025, lengleng All rights reserved.
 *
 */

package com.aiwork.baas.ddl.inspect;

import com.aiwork.baas.ddl.type.ColumnType;
import com.aiwork.baas.ddl.type.LogicalColumn;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ACTIVE 准入谓词单元测试。
 *
 * @author ai-work
 * @date 2026/07/19
 */
class AdmissionPredicateTest {

    private static final PhysicalDatabase DATABASE = new PhysicalDatabase("utf8mb4", "utf8mb4_general_ci");

    @Test
    void admissibleTableMapsCompleteLogicalColumnsAndIndexFlags() {
        PhysicalColumn email = varcharColumn("email", 255L);
        PhysicalColumn age = intColumn("age");
        PhysicalTable table = table(List.of(idColumn(), email, age), List.of(primaryIndex(),
                index("foo_email", true, part("email")), index("foo_age", false, part("age"))));

        MappingOutcome<List<LogicalColumn>> outcome = AdmissionPredicate.evaluate(DATABASE, table);

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.value()).hasSize(3);
        assertThat(outcome.value()).filteredOn(column -> "email".equals(column.columnName()))
            .hasSize(1)
            .first()
            .satisfies(column -> {
                assertThat(column.type()).isEqualTo(ColumnType.VARCHAR);
                assertThat(column.unique()).isTrue();
                assertThat(column.indexed()).isFalse();
            });
        assertThat(outcome.value()).filteredOn(column -> "age".equals(column.columnName()))
            .hasSize(1)
            .first()
            .satisfies(column -> {
                assertThat(column.type()).isEqualTo(ColumnType.INT);
                assertThat(column.unique()).isFalse();
                assertThat(column.indexed()).isTrue();
            });
    }

    @Test
    void tableLevelUnsupportedStructuresFailClosed() {
        assertRejected(DATABASE, table("a_view", "VIEW", "InnoDB", "Dynamic", "utf8mb4_general_ci",
                false, false, false, List.of(), List.of()), "VIEW");
        assertRejected(DATABASE, table("external_table", "BASE TABLE", "MyISAM", "Dynamic",
                "utf8mb4_general_ci", false, false, false, List.of(idColumn()), List.of(primaryIndex())),
                "InnoDB");
        assertRejected(DATABASE, table("external_table", "BASE TABLE", "InnoDB", "Dynamic",
                "utf8mb4_general_ci", true, false, false, List.of(idColumn()), List.of(primaryIndex())),
                "触发器");
        assertRejected(DATABASE, table("external_table", "BASE TABLE", "InnoDB", "Dynamic",
                "utf8mb4_general_ci", false, true, false, List.of(idColumn()), List.of(primaryIndex())),
                "外键");
        assertRejected(DATABASE, table("external_table", "BASE TABLE", "InnoDB", "Dynamic",
                "utf8mb4_general_ci", false, false, true, List.of(idColumn()), List.of(primaryIndex())),
                "CHECK");
        assertRejected(DATABASE, table("BadName", "BASE TABLE", "InnoDB", "Dynamic",
                "utf8mb4_general_ci", false, false, false, List.of(idColumn()), List.of(primaryIndex())),
                "标识符");
    }

    @Test
    void databaseAndTablePhysicalBaselineDriftFailsClosed() {
        assertRejected(new PhysicalDatabase("latin1", "latin1_swedish_ci"), table(), "库级");
        assertRejected(DATABASE, table("external_table", "BASE TABLE", "InnoDB", "Compact",
                "utf8mb4_general_ci", false, false, false, List.of(idColumn()), List.of(primaryIndex())),
                "表级");
        assertRejected(DATABASE, table("external_table", "BASE TABLE", "InnoDB", "Dynamic",
                "utf8mb4_0900_ai_ci", false, false, false, List.of(idColumn()), List.of(primaryIndex())),
                "表级");
        PhysicalColumn wrongCharset = new PhysicalColumn("name", "varchar", "varchar(32)", 32L, null, null,
                null, true, null, "", "latin1", "latin1_swedish_ci", "", "", "");
        assertRejected(DATABASE, table(List.of(idColumn(), wrongCharset), List.of(primaryIndex())), "字符集");
    }

    @Test
    void primaryKeyInvariantDriftFailsClosed() {
        assertRejected(DATABASE, table(List.of(intColumn("name")), List.of()), "主键");
        PhysicalColumn nonAutoId = new PhysicalColumn("id", "bigint", "bigint", null, 19L, 0L, null,
                false, null, "", null, null, "PRI", "", "");
        assertRejected(DATABASE, table(List.of(nonAutoId), List.of(primaryIndex())), "主键");
        PhysicalColumn unsignedId = new PhysicalColumn("id", "bigint", "bigint unsigned", null, 20L, 0L,
                null, false, null, "auto_increment", null, null, "PRI", "", "");
        assertRejected(DATABASE, table(List.of(unsignedId), List.of(primaryIndex())), "主键");
        PhysicalColumn extraPk = new PhysicalColumn("tenant_id", "bigint", "bigint", null, 19L, 0L, null,
                false, null, "", null, null, "PRI", "", "");
        assertRejected(DATABASE, table(List.of(idColumn(), extraPk), List.of(primaryIndex())), "主键");
    }

    @Test
    void bigintDisplayWidthDoesNotCreateFalsePrimaryKeyDrift() {
        PhysicalColumn displayWidthId = new PhysicalColumn("id", "bigint", "bigint(20)", null, 19L, 0L,
                null, false, null, "auto_increment", null, null, "PRI", "", "");

        MappingOutcome<List<LogicalColumn>> outcome = AdmissionPredicate.evaluate(DATABASE,
                table(List.of(displayWidthId), List.of(primaryIndex())));

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.value()).hasSize(1);
        LogicalColumn column = outcome.value().get(0);
        assertThat(column.type()).isEqualTo(ColumnType.BIGINT);
        assertThat(column.pk()).isTrue();
        assertThat(column.autoIncrement()).isTrue();
    }

    @Test
    void unsupportedColumnShapesFailClosed() {
        PhysicalColumn invalidIdentifier = intColumn("BadColumn");
        assertRejected(DATABASE, table(List.of(idColumn(), invalidIdentifier), List.of(primaryIndex())), "标识符");
        PhysicalColumn floating = new PhysicalColumn("amount", "float", "float", null, 12L, null, null,
                true, null, "", null, null, "", "", "");
        assertRejected(DATABASE, table(List.of(idColumn(), floating), List.of(primaryIndex())), "白名单");
        PhysicalColumn generated = new PhysicalColumn("derived", "int", "int", null, 10L, 0L, null,
                true, null, "VIRTUAL GENERATED", null, null, "", "", "id + 1");
        assertRejected(DATABASE, table(List.of(idColumn(), generated), List.of(primaryIndex())), "生成列");
        PhysicalColumn onUpdate = new PhysicalColumn("touched", "datetime", "datetime", null, null, null, 0L,
                true, "CURRENT_TIMESTAMP", "DEFAULT_GENERATED on update CURRENT_TIMESTAMP", null, null, "", "",
                "");
        assertRejected(DATABASE, table(List.of(idColumn(), onUpdate), List.of(primaryIndex())), "EXTRA");
    }

    @Test
    void defaultsOutsideTypedLogicalModelFailClosed() {
        PhysicalColumn invalidBoolean = new PhysicalColumn("vip", "tinyint", "tinyint(1)", null, 3L, 0L,
                null, true, "2", "", null, null, "", "", "");
        assertRejected(DATABASE, table(List.of(idColumn(), invalidBoolean), List.of(primaryIndex())), "默认值");

        PhysicalColumn invalidDatetime = new PhysicalColumn("created", "datetime", "datetime", null, null,
                null, 0L, true, "2026-02-30 12:00:00", "", null, null, "", "", "");
        assertRejected(DATABASE, table(List.of(idColumn(), invalidDatetime), List.of(primaryIndex())), "默认值");

        PhysicalColumn invalidVarchar = new PhysicalColumn("label", "varchar", "varchar(64)", 64L, null,
                null, null, true, "CURRENT_TIMESTAMP()", "", "utf8mb4", "utf8mb4_general_ci", "", "", "");
        assertRejected(DATABASE, table(List.of(idColumn(), invalidVarchar), List.of(primaryIndex())), "默认值");
    }

    @Test
    void nonMappableAndDuplicateIndexesFailClosed() {
        PhysicalColumn name = varcharColumn("name", 300L);
        PhysicalTable base = table(List.of(idColumn(), name), List.of(primaryIndex()));
        assertRejected(DATABASE, withIndex(base, index("idx_composite", false, part("name"), part("id"))),
                "索引");
        assertRejected(DATABASE, withIndex(base, index("idx_prefix", false,
                new PhysicalIndex.Part("name", null, 10L, "BTREE", "YES", "A"))), "索引");
        assertRejected(DATABASE, withIndex(base, index("idx_expression", false,
                new PhysicalIndex.Part(null, "lower(`name`)", null, "BTREE", "YES", "A"))), "索引");
        assertRejected(DATABASE, withIndex(base, index("idx_fulltext", false,
                new PhysicalIndex.Part("name", null, null, "FULLTEXT", "YES", null))), "索引");
        assertRejected(DATABASE, withIndex(base, index("idx_invisible", false,
                new PhysicalIndex.Part("name", null, null, "BTREE", "NO", "A"))), "索引");
        assertRejected(DATABASE, withIndex(base, index("idx_desc", false,
                new PhysicalIndex.Part("name", null, null, "BTREE", "YES", "D"))), "索引");
        PhysicalTable duplicate = table(List.of(idColumn(), name), List.of(primaryIndex(),
                index("idx_name", false, part("name")), index("uk_name", true, part("name"))));
        assertRejected(DATABASE, duplicate, "重复");
    }

    private static PhysicalTable table() {
        return table(List.of(idColumn()), List.of(primaryIndex()));
    }

    private static PhysicalTable table(List<PhysicalColumn> columns, List<PhysicalIndex> indexes) {
        return table("external_table", "BASE TABLE", "InnoDB", "Dynamic", "utf8mb4_general_ci", false,
                false, false, columns, indexes);
    }

    private static PhysicalTable table(String tableName, String tableType, String engine, String rowFormat,
            String collation, boolean triggers, boolean foreignKeys, boolean checks, List<PhysicalColumn> columns,
            List<PhysicalIndex> indexes) {
        return new PhysicalTable(tableName, tableType, engine, rowFormat, collation, "", triggers, foreignKeys,
                checks, columns, indexes);
    }

    private static PhysicalTable withIndex(PhysicalTable base, PhysicalIndex index) {
        return table(base.columns(), List.of(primaryIndex(), index));
    }

    private static PhysicalColumn idColumn() {
        return new PhysicalColumn("id", "bigint", "bigint", null, 19L, 0L, null, false, null,
                "auto_increment", null, null, "PRI", "", "");
    }

    private static PhysicalColumn intColumn(String name) {
        return new PhysicalColumn(name, "int", "int", null, 10L, 0L, null, true, null, "", null, null, "", "",
                "");
    }

    private static PhysicalColumn varcharColumn(String name, Long length) {
        return new PhysicalColumn(name, "varchar", "varchar(" + length + ")", length, null, null, null, true,
                null, "", "utf8mb4", "utf8mb4_general_ci", "", "", "");
    }

    private static PhysicalIndex primaryIndex() {
        return index("PRIMARY", true, part("id"));
    }

    private static PhysicalIndex index(String name, boolean unique, PhysicalIndex.Part... parts) {
        return new PhysicalIndex(name, unique, List.of(parts));
    }

    private static PhysicalIndex.Part part(String columnName) {
        return new PhysicalIndex.Part(columnName, null, null, "BTREE", "YES", "A");
    }

    private static void assertRejected(PhysicalDatabase database, PhysicalTable table, String reasonFragment) {
        MappingOutcome<List<LogicalColumn>> outcome = AdmissionPredicate.evaluate(database, table);
        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.rejectReason()).containsIgnoringCase(reasonFragment);
    }

}
