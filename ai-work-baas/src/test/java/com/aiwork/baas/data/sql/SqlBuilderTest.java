package com.aiwork.baas.data.sql;

import com.aiwork.baas.data.bind.ValueCodec;
import com.aiwork.baas.data.bind.WriteBodyParser;
import com.aiwork.baas.data.config.DataPlaneProperties;
import com.aiwork.baas.data.context.DataRequestContext;
import com.aiwork.baas.data.context.DataRole;
import com.aiwork.baas.data.error.DataApiException;
import com.aiwork.baas.data.meta.TableMeta;
import com.aiwork.baas.data.query.ParsedQuery;
import com.aiwork.baas.data.query.QueryParser;
import com.aiwork.baas.entity.BaasColumn;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.BaasTable;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SQL 构建单测:owner 注入、允许列表、参数化与注入载荷(spec §7.1/§8.3/§12.2/§14)。
 *
 * @author ai-work
 * @date 2026/07/21
 */
class SqlBuilderTest {

    private final SqlBuilder builder = new SqlBuilder(new ValueCodec());

    private final QueryParser queryParser = new QueryParser(new DataPlaneProperties());

    private static TableMeta meta(String ownerColumn) {
        BaasTable table = new BaasTable();
        table.setTableName("orders");
        table.setOwnerColumn(ownerColumn);
        List<BaasColumn> columns = new java.util.ArrayList<>();
        columns.add(col("id", "bigint"));
        columns.add(col("title", "varchar"));
        columns.add(col("qty", "int"));
        columns.add(col("owner_id", "bigint"));
        Map<String, BaasColumn> byName = new LinkedHashMap<>();
        columns.forEach(c -> byName.put(c.getColumnName(), c));
        return new TableMeta(table, columns, byName, Map.of());
    }

    private static BaasColumn col(String name, String dataType) {
        BaasColumn column = new BaasColumn();
        column.setColumnName(name);
        column.setDataType(dataType);
        column.setNullable(true);
        if ("varchar".equals(dataType)) {
            column.setLength(64);
        }
        return column;
    }

    private static DataRequestContext ctx(DataRole role, Long userId) {
        return new DataRequestContext(new BaasProject(), role, userId, userId == null ? null : 1L);
    }

    private ParsedQuery query(String... pairs) {
        Map<String, String[]> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], new String[] { pairs[i + 1] });
        }
        return queryParser.parse(map);
    }

    @Test
    void selectInjectsOwnerEqualsForAuthenticated() {
        SelectPlan plan = builder.buildSelect(meta("owner_id"), query("qty", "gte.2"),
                ctx(DataRole.AUTHENTICATED, 7L));

        assertThat(plan.boundSql().sql()).isEqualTo(
                "SELECT `id`, `title`, `qty`, `owner_id` FROM `orders` WHERE `qty` >= ? AND `owner_id` = ? "
                        + "LIMIT ? OFFSET ?");
        assertThat(plan.boundSql().params()).containsExactly(2, 7L, 100, 0);
    }

    @Test
    void selectInjectsOwnerIsNullForAnon() {
        SelectPlan plan = builder.buildSelect(meta("owner_id"), query(), ctx(DataRole.ANON, null));

        assertThat(plan.boundSql().sql()).contains("WHERE `owner_id` IS NULL");
    }

    @Test
    void serviceRoleGetsNoOwnerInjection() {
        SelectPlan plan = builder.buildSelect(meta("owner_id"), query(), ctx(DataRole.SERVICE_ROLE, null));

        assertThat(plan.boundSql().sql()).doesNotContain("owner_id` = ?").doesNotContain("IS NULL");
    }

    @Test
    void selectProjectionAndOrder() {
        SelectPlan plan = builder.buildSelect(meta(null),
                query("select", "id,title", "order", "qty.desc,id", "limit", "5", "offset", "10"),
                ctx(DataRole.SERVICE_ROLE, null));

        assertThat(plan.boundSql().sql()).isEqualTo(
                "SELECT `id`, `title` FROM `orders` ORDER BY `qty` DESC, `id` ASC LIMIT ? OFFSET ?");
        assertThat(plan.outputColumns()).extracting(BaasColumn::getColumnName).containsExactly("id", "title");
    }

    @Test
    void unknownFilterSelectOrOrderColumnRejected() {
        assertThatThrownBy(
                () -> builder.buildSelect(meta(null), query("nope", "eq.1"), ctx(DataRole.SERVICE_ROLE, null)))
            .isInstanceOf(DataApiException.class);
        assertThatThrownBy(() -> builder.buildSelect(meta(null), query("select", "id,nope"),
                ctx(DataRole.SERVICE_ROLE, null)))
            .isInstanceOf(DataApiException.class);
        assertThatThrownBy(() -> builder.buildSelect(meta(null), query("order", "nope.desc"),
                ctx(DataRole.SERVICE_ROLE, null)))
            .isInstanceOf(DataApiException.class);
    }

    @Test
    void injectionPayloadColumnNameIsRejectedNotConcatenated() {
        assertThatThrownBy(() -> builder.buildSelect(meta(null), query("id`;DROP TABLE orders;--", "eq.1"),
                ctx(DataRole.SERVICE_ROLE, null)))
            .isInstanceOf(DataApiException.class);

        WriteBodyParser.ParsedWrite write = new WriteBodyParser.ParsedWrite(
                List.of("title`;DROP TABLE orders;--"), List.of(java.util.List.of("x")));
        assertThatThrownBy(() -> builder.buildInsert(meta(null), write)).isInstanceOf(DataApiException.class);

        BaasColumn outputColumn = col("title`;DROP TABLE orders;--", "varchar");
        assertThatThrownBy(() -> builder.buildSelectByIds(meta(null), List.of(outputColumn), List.of(1L)))
            .isInstanceOf(DataApiException.class);

        assertThatThrownBy(() -> builder.buildSelect(meta("owner_id`;DROP TABLE orders;--"), query(),
                ctx(DataRole.ANON, null)))
            .isInstanceOf(DataApiException.class);
    }

    @Test
    void injectionPayloadValueStaysParameterized() {
        SelectPlan plan = builder.buildSelect(meta(null), query("title", "eq.a' OR '1'='1"),
                ctx(DataRole.SERVICE_ROLE, null));

        assertThat(plan.boundSql().sql()).doesNotContain("OR '1'='1");
        assertThat(plan.boundSql().params()).contains("a' OR '1'='1");
    }

    @Test
    void inBuildsPlaceholderPerElement() {
        SelectPlan plan = builder.buildSelect(meta(null), query("qty", "in.(1,2,3)"),
                ctx(DataRole.SERVICE_ROLE, null));

        assertThat(plan.boundSql().sql()).contains("`qty` IN (?, ?, ?)");
        assertThat(plan.boundSql().params()).contains(1, 2, 3);
    }

    @Test
    void updateAndDeleteWithoutFilterRejected() {
        WriteBodyParser.ParsedWrite write = new WriteBodyParser.ParsedWrite(List.of("title"),
                List.of(java.util.List.of("x")));

        assertThatThrownBy(() -> builder.buildUpdate(meta(null), query(), write, ctx(DataRole.SERVICE_ROLE, null)))
            .isInstanceOf(DataApiException.class);
        assertThatThrownBy(() -> builder.buildDelete(meta(null), query(), ctx(DataRole.SERVICE_ROLE, null)))
            .isInstanceOf(DataApiException.class);
        assertThatThrownBy(
                () -> builder.buildCaptureIds(meta(null), query(), ctx(DataRole.SERVICE_ROLE, null), 1001))
            .isInstanceOf(DataApiException.class);
    }

    @Test
    void captureIdsUsesForUpdateWithProbeLimit() {
        BoundSql bound = builder.buildCaptureIds(meta("owner_id"), query("qty", "eq.1"),
                ctx(DataRole.AUTHENTICATED, 7L), 1001);

        assertThat(bound.sql()).isEqualTo(
                "SELECT `id` FROM `orders` WHERE `qty` = ? AND `owner_id` = ? LIMIT ? FOR UPDATE");
        assertThat(bound.params()).containsExactly(1, 7L, 1001);
    }

    @Test
    void updateByIdsAndSelectByIds() {
        WriteBodyParser.ParsedWrite write = new WriteBodyParser.ParsedWrite(List.of("title"),
                List.of(java.util.List.of("x")));
        TableMeta meta = meta(null);

        BoundSql update = builder.buildUpdateByIds(meta, write, List.of(1L, 2L));
        assertThat(update.sql()).isEqualTo("UPDATE `orders` SET `title` = ? WHERE `id` IN (?, ?)");
        assertThat(update.params()).containsExactly("x", 1L, 2L);

        BoundSql select = builder.buildSelectByIds(meta, meta.columns(), List.of(1L, 2L));
        assertThat(select.sql())
            .isEqualTo("SELECT `id`, `title`, `qty`, `owner_id` FROM `orders` WHERE `id` IN (?, ?) ORDER BY `id` ASC");
    }

    @Test
    void insertBuildsMultiRowValues() {
        WriteBodyParser.ParsedWrite write = new WriteBodyParser.ParsedWrite(List.of("title", "qty"),
                List.of(java.util.List.of("a", 1), java.util.List.of("b", 2)));

        BoundSql bound = builder.buildInsert(meta(null), write);

        assertThat(bound.sql()).isEqualTo("INSERT INTO `orders` (`title`, `qty`) VALUES (?, ?), (?, ?)");
        assertThat(bound.params()).containsExactly("a", 1, "b", 2);
    }

    @Test
    void sqlNullStaysBoundParameter() {
        WriteBodyParser.ParsedWrite write = new WriteBodyParser.ParsedWrite(List.of("title"),
                List.of(java.util.Arrays.asList((Object)null)));

        BoundSql bound = builder.buildInsert(meta(null), write);

        assertThat(bound.sql()).isEqualTo("INSERT INTO `orders` (`title`) VALUES (?)");
        assertThat(bound.params()).hasSize(1);
        assertThat(bound.params().get(0)).isNull();
    }

    @Test
    void directUpdateInjectsOwnerFilter() {
        WriteBodyParser.ParsedWrite write = new WriteBodyParser.ParsedWrite(List.of("title"),
                List.of(java.util.List.of("x")));

        BoundSql bound = builder.buildUpdate(meta("owner_id"), query("qty", "eq.1"), write,
                ctx(DataRole.ANON, null));

        assertThat(bound.sql())
            .isEqualTo("UPDATE `orders` SET `title` = ? WHERE `qty` = ? AND `owner_id` IS NULL");
        assertThat(bound.params()).containsExactly("x", 1);
    }

    @Test
    void countUsesSameWhere() {
        BoundSql bound = builder.buildCount(meta("owner_id"), query("qty", "gte.2"),
                ctx(DataRole.AUTHENTICATED, 7L));

        assertThat(bound.sql()).isEqualTo("SELECT COUNT(*) FROM `orders` WHERE `qty` >= ? AND `owner_id` = ?");
        assertThat(bound.params()).containsExactly(2, 7L);
    }

    @Test
    void jsonColumnOnlyIsOperator() {
        TableMeta meta = meta(null);
        BaasColumn payload = col("payload", "json");
        meta.columns().add(payload);
        meta.columnsByName().put("payload", payload);

        SelectPlan isNull = builder.buildSelect(meta, query("payload", "is.null"), ctx(DataRole.SERVICE_ROLE, null));
        assertThat(isNull.boundSql().sql()).contains("`payload` IS NULL");

        assertThatThrownBy(
                () -> builder.buildSelect(meta, query("payload", "eq.x"), ctx(DataRole.SERVICE_ROLE, null)))
            .isInstanceOf(DataApiException.class);
    }

}
