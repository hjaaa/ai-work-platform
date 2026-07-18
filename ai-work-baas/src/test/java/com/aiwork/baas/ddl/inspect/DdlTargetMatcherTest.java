package com.aiwork.baas.ddl.inspect;

import com.aiwork.baas.ddl.type.ColumnType;
import com.aiwork.baas.ddl.type.LogicalColumn;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DdlTargetMatcherTest {

    private static final LogicalColumn ID = new LogicalColumn("id", ColumnType.BIGINT, null, null, false, null,
            true, true, false, false, null);

    private static final LogicalColumn EMAIL = new LogicalColumn("email", ColumnType.VARCHAR, 64, null, true,
            "a@example.com", false, false, true, false, "邮箱");

    @Test
    void exactNormalizedTargetMatches() {
        assertThat(DdlTargetMatcher.matches(table("demo", "表注释", true, true), "demo", "表注释",
                List.of(ID, EMAIL))).isTrue();
    }

    @Test
    void nullableAndTableCommentDifferencesDoNotMatchButNonCanonicalIndexNameCanMatch() {
        assertThat(DdlTargetMatcher.matches(table("demo", "旧注释", true, true), "demo", "表注释",
                List.of(ID, EMAIL))).isFalse();
        assertThat(DdlTargetMatcher.matches(table("demo", "表注释", false, true), "demo", "表注释",
                List.of(ID, EMAIL))).isFalse();
        assertThat(DdlTargetMatcher.matches(table("demo", "表注释", true, false), "demo", "表注释",
                List.of(ID, EMAIL))).isTrue();
    }

    private PhysicalTable table(String name, String comment, boolean nullable, boolean canonicalIndexName) {
        PhysicalColumn id = new PhysicalColumn("id", "bigint", "bigint", null, 19L, 0L, null, false, null,
                "auto_increment", null, null, "PRI", "", "");
        PhysicalColumn email = new PhysicalColumn("email", "varchar", "varchar(64)", 64L, null, null, null,
                nullable, "a@example.com", "", "utf8mb4", "utf8mb4_general_ci", "UNI", "邮箱", "");
        PhysicalIndex primary = new PhysicalIndex("PRIMARY", true,
                List.of(new PhysicalIndex.Part("id", null, null, "BTREE", "YES", "A")));
        PhysicalIndex unique = new PhysicalIndex(canonicalIndexName ? "uk_email" : "foo_email", true,
                List.of(new PhysicalIndex.Part("email", null, null, "BTREE", "YES", "A")));
        return new PhysicalTable(name, "BASE TABLE", "InnoDB", "Dynamic", "utf8mb4_general_ci", comment,
                false, false, false, List.of(id, email), List.of(primary, unique));
    }

}
