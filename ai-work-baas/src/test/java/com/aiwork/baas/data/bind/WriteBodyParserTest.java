package com.aiwork.baas.data.bind;

import com.aiwork.baas.data.context.DataRequestContext;
import com.aiwork.baas.data.context.DataRole;
import com.aiwork.baas.data.error.DataApiException;
import com.aiwork.baas.data.meta.TableMeta;
import com.aiwork.baas.entity.BaasColumn;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.BaasTable;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * body 解析规则(spec §7.1:null/缺失/批量键集合;§8.3:owner/id 保护)。
 *
 * @author ai-work
 * @date 2026/07/21
 */
class WriteBodyParserTest {

    private final WriteBodyParser parser = new WriteBodyParser(new ValueCodec());

    private final ObjectMapper mapper = JsonMapper.builder()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .build();

    private static BaasColumn column(String name, String dataType, boolean nullable) {
        BaasColumn column = new BaasColumn();
        column.setColumnName(name);
        column.setDataType(dataType);
        column.setNullable(nullable);
        if ("varchar".equals(dataType)) {
            column.setLength(64);
        }
        return column;
    }

    private static TableMeta meta(String ownerColumn) {
        BaasTable table = new BaasTable();
        table.setTableName("orders");
        table.setOwnerColumn(ownerColumn);
        List<BaasColumn> columns = List.of(column("id", "bigint", false), column("title", "varchar", true),
                column("qty", "int", true), column("owner_id", "bigint", true));
        Map<String, BaasColumn> byName = new LinkedHashMap<>();
        columns.forEach(c -> byName.put(c.getColumnName(), c));
        return new TableMeta(table, columns, byName, Map.of());
    }

    private static DataRequestContext ctx(DataRole role, Long userId) {
        return new DataRequestContext(new BaasProject(), role, userId, userId == null ? null : 1L);
    }

    @Test
    void insertForcesOwnerForAuthenticated() throws Exception {
        WriteBodyParser.ParsedWrite write = parser.parseInsert(meta("owner_id"),
                mapper.readTree("{\"title\":\"a\"}"), ctx(DataRole.AUTHENTICATED, 7L), 1000);

        assertThat(write.columns()).containsExactly("title", "owner_id");
        assertThat(write.rows()).containsExactly(java.util.Arrays.asList("a", 7L));
    }

    @Test
    void insertForcesOwnerNullForAnon() throws Exception {
        WriteBodyParser.ParsedWrite write = parser.parseInsert(meta("owner_id"), mapper.readTree("{\"title\":\"a\"}"),
                ctx(DataRole.ANON, null), 1000);

        assertThat(write.columns()).containsExactly("title", "owner_id");
        assertThat(write.rows().get(0).get(1)).isNull();
    }

    @Test
    void bodyWithOwnerColumnRejectedForNonServiceRole() throws Exception {
        assertThatThrownBy(() -> parser.parseInsert(meta("owner_id"), mapper.readTree("{\"owner_id\":9}"),
                ctx(DataRole.AUTHENTICATED, 7L), 1000))
            .isInstanceOf(DataApiException.class);
        assertThatThrownBy(() -> parser.parsePatch(meta("owner_id"), mapper.readTree("{\"owner_id\":9}"),
                ctx(DataRole.ANON, null)))
            .isInstanceOf(DataApiException.class);
    }

    @Test
    void serviceRoleMayWriteOwner() throws Exception {
        WriteBodyParser.ParsedWrite write = parser.parseInsert(meta("owner_id"), mapper.readTree("{\"owner_id\":9}"),
                ctx(DataRole.SERVICE_ROLE, null), 1000);

        assertThat(write.columns()).containsExactly("owner_id");
        assertThat(write.rows()).containsExactly(java.util.List.of(9L));
    }

    @Test
    void idColumnAlwaysRejected() throws Exception {
        assertThatThrownBy(() -> parser.parseInsert(meta(null), mapper.readTree("{\"id\":1,\"title\":\"a\"}"),
                ctx(DataRole.SERVICE_ROLE, null), 1000))
            .isInstanceOf(DataApiException.class)
            .hasMessageContaining("id");
    }

    @Test
    void unknownColumnRejected() throws Exception {
        assertThatThrownBy(() -> parser.parseInsert(meta(null), mapper.readTree("{\"nope\":1}"),
                ctx(DataRole.SERVICE_ROLE, null), 1000))
            .isInstanceOf(DataApiException.class);
    }

    @Test
    void explicitNullBindsSqlNullWhenNullable() throws Exception {
        WriteBodyParser.ParsedWrite write = parser.parseInsert(meta(null), mapper.readTree("{\"title\":null}"),
                ctx(DataRole.SERVICE_ROLE, null), 1000);

        assertThat(write.rows().get(0).get(0)).isNull();
    }

    @Test
    void explicitNullOnNotNullColumnRejected() throws Exception {
        TableMeta meta = meta(null);
        BaasColumn qty = meta.columnsByName().get("qty");
        qty.setNullable(false);

        assertThatThrownBy(() -> parser.parseInsert(meta, mapper.readTree("{\"qty\":null}"),
                ctx(DataRole.SERVICE_ROLE, null), 1000))
            .isInstanceOf(DataApiException.class);
    }

    @Test
    void batchKeySetMustBeConsistent() throws Exception {
        assertThatThrownBy(() -> parser.parseInsert(meta(null),
                mapper.readTree("[{\"title\":\"a\"},{\"title\":\"b\",\"qty\":1}]"), ctx(DataRole.SERVICE_ROLE, null),
                1000))
            .isInstanceOf(DataApiException.class)
            .hasMessageContaining("字段集合");
    }

    @Test
    void batchOverLimitRejected() throws Exception {
        assertThatThrownBy(() -> parser.parseInsert(meta(null), mapper.readTree("[{\"qty\":1},{\"qty\":2}]"),
                ctx(DataRole.SERVICE_ROLE, null), 1))
            .isInstanceOf(DataApiException.class);
    }

    @Test
    void emptyArrayAndNonObjectRejected() throws Exception {
        assertThatThrownBy(() -> parser.parseInsert(meta(null), mapper.readTree("[]"),
                ctx(DataRole.SERVICE_ROLE, null), 1000))
            .isInstanceOf(DataApiException.class);
        assertThatThrownBy(() -> parser.parseInsert(meta(null), mapper.readTree("\"str\""),
                ctx(DataRole.SERVICE_ROLE, null), 1000))
            .isInstanceOf(DataApiException.class);
    }

    @Test
    void patchRequiresAtLeastOneField() throws Exception {
        assertThatThrownBy(() -> parser.parsePatch(meta(null), mapper.readTree("{}"),
                ctx(DataRole.SERVICE_ROLE, null)))
            .isInstanceOf(DataApiException.class);
    }

}
