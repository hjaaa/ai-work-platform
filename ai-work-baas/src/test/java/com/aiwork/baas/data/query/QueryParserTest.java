package com.aiwork.baas.data.query;

import com.aiwork.baas.data.config.DataPlaneProperties;
import com.aiwork.baas.data.error.DataApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 查询语法解析单测(spec §7.1 操作符与语义细则)。
 *
 * @author ai-work
 * @date 2026/07/21
 */
class QueryParserTest {

    private final QueryParser parser = new QueryParser(new DataPlaneProperties());

    private static Map<String, String[]> params(String... pairs) {
        Map<String, String[]> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            String key = pairs[i];
            String value = pairs[i + 1];
            String[] existing = map.get(key);
            if (existing == null) {
                map.put(key, new String[] { value });
            } else {
                String[] merged = new String[existing.length + 1];
                System.arraycopy(existing, 0, merged, 0, existing.length);
                merged[existing.length] = value;
                map.put(key, merged);
            }
        }
        return map;
    }

    @ParameterizedTest
    @CsvSource({ "eq,EQ", "neq,NEQ", "gt,GT", "gte,GTE", "lt,LT", "lte,LTE", "like,LIKE" })
    void parsesScalarOperators(String token, String expected) {
        ParsedQuery query = parser.parse(params("age", token + ".18"));

        assertThat(query.filters()).hasSize(1);
        assertThat(query.filters().get(0).column()).isEqualTo("age");
        assertThat(query.filters().get(0).operator()).isEqualTo(FilterOperator.valueOf(expected));
        assertThat(query.filters().get(0).rawValue()).isEqualTo("18");
    }

    @Test
    void parsesInList() {
        ParsedQuery query = parser.parse(params("status", "in.(a,b,c)"));

        assertThat(query.filters().get(0).operator()).isEqualTo(FilterOperator.IN);
        assertThat(query.filters().get(0).inValues()).containsExactly("a", "b", "c");
        assertThat(query.filters().get(0).rawValue()).isNull();
    }

    @Test
    void parsesIsNullAndNotNull() {
        ParsedQuery query = parser.parse(params("tag", "is.null", "tag", "is.not_null"));

        assertThat(query.filters()).extracting(FilterCondition::rawValue).containsExactly("null", "not_null");
    }

    @Test
    void sameColumnRepeatedBecomesAndConditions() {
        ParsedQuery query = parser.parse(params("age", "gte.18", "age", "lt.30"));

        assertThat(query.filters()).hasSize(2);
    }

    @Test
    void parsesSelectOrderLimitOffset() {
        ParsedQuery query = parser
            .parse(params("select", "id,name", "order", "created_at.desc,name", "limit", "20", "offset", "40"));

        assertThat(query.select()).containsExactly("id", "name");
        assertThat(query.order()).containsExactly(new OrderTerm("created_at", false), new OrderTerm("name", true));
        assertThat(query.limit()).isEqualTo(20);
        assertThat(query.offset()).isEqualTo(40);
    }

    @Test
    void defaultsWhenAbsent() {
        ParsedQuery query = parser.parse(params());

        assertThat(query.select()).isNull();
        assertThat(query.order()).isEmpty();
        assertThat(query.limit()).isEqualTo(100);
        assertThat(query.offset()).isZero();
    }

    @Test
    void valueDotsAfterOperatorAreKept() {
        ParsedQuery query = parser.parse(params("price", "gt.1.5"));

        assertThat(query.filters().get(0).rawValue()).isEqualTo("1.5");
    }

    @ParameterizedTest
    @CsvSource(value = { "age|18", "age|foo.18", "status|in.()", "status|in.(a,'b')", "status|in.(a,,b)",
            "tag|is.maybe", "limit|0", "limit|1001", "limit|abc", "offset|-1" }, delimiter = '|')
    void rejectsIllegalSyntax(String key, String value) {
        assertThatThrownBy(() -> parser.parse(params(key, value))).isInstanceOf(DataApiException.class)
            .satisfies(exception -> assertThat(((DataApiException)exception).status()).isEqualTo(400));
    }

    @Test
    void rejectsMoreThanMaxFilters() {
        String[] pairs = new String[21 * 2];
        for (int i = 0; i < 21; i++) {
            pairs[i * 2] = "c" + i;
            pairs[i * 2 + 1] = "eq.1";
        }

        assertThatThrownBy(() -> parser.parse(params(pairs))).isInstanceOf(DataApiException.class)
            .hasMessageContaining("过滤条件数量");
    }

    @Test
    void rejectsUnknownOperator() {
        assertThatThrownBy(() -> parser.parse(params("age", "between.1"))).isInstanceOf(DataApiException.class)
            .hasMessageContaining("不支持的操作符");
    }

}
