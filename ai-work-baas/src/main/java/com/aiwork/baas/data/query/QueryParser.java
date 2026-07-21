package com.aiwork.baas.data.query;

import com.aiwork.baas.data.config.DataPlaneProperties;
import com.aiwork.baas.data.error.DataApiException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 查询串解析(spec §7.1);只做语法层,列名白名单校验在 SqlBuilder 按元数据执行。
 *
 * @author ai-work
 * @date 2026/07/21
 */
@Component
public class QueryParser {

    private static final Set<String> RESERVED_PARAMS = Set.of("select", "order", "limit", "offset");

    private final DataPlaneProperties properties;

    public QueryParser(DataPlaneProperties properties) {
        this.properties = properties;
    }

    /**
     * 解析 HTTP 查询参数。
     *
     * @param parameterMap HTTP 查询参数
     * @return 结构化查询
     */
    public ParsedQuery parse(Map<String, String[]> parameterMap) {
        List<FilterCondition> filters = new ArrayList<>();
        List<String> select = null;
        List<OrderTerm> order = List.of();
        int limit = properties.getDefaultLimit();
        int offset = 0;

        for (Map.Entry<String, String[]> entry : parameterMap.entrySet()) {
            String key = entry.getKey();
            String[] values = entry.getValue();
            if (RESERVED_PARAMS.contains(key)) {
                String value = singleValue(key, values);
                switch (key) {
                    case "select" -> select = parseSelect(value);
                    case "order" -> order = parseOrder(value);
                    case "limit" -> limit = parseBoundedInt(value, 1, properties.getMaxLimit(), "limit");
                    case "offset" -> offset = parseBoundedInt(value, 0, Integer.MAX_VALUE, "offset");
                    default -> throw new IllegalStateException(key);
                }
                continue;
            }
            for (String value : values) {
                filters.add(parseFilter(key, value));
            }
        }
        if (filters.size() > properties.getMaxFilters()) {
            throw DataApiException.badRequest("过滤条件数量超过上限 " + properties.getMaxFilters());
        }
        return new ParsedQuery(List.copyOf(filters), select, order, limit, offset);
    }

    private static String singleValue(String key, String[] values) {
        if (values.length != 1) {
            throw DataApiException.badRequest(key + " 参数只允许出现一次");
        }
        return values[0];
    }

    private static FilterCondition parseFilter(String column, String value) {
        int dot = value.indexOf('.');
        if (dot <= 0) {
            throw DataApiException.badRequest("过滤条件必须为 op.value 形式: " + column);
        }
        FilterOperator operator = FilterOperator.fromToken(value.substring(0, dot));
        String raw = value.substring(dot + 1);
        if (operator == FilterOperator.IN) {
            return new FilterCondition(column, operator, null, parseInList(column, raw));
        }
        if (operator == FilterOperator.IS) {
            if (!"null".equals(raw) && !"not_null".equals(raw)) {
                throw DataApiException.badRequest("is 仅支持 is.null / is.not_null: " + column);
            }
            return new FilterCondition(column, operator, raw, null);
        }
        return new FilterCondition(column, operator, raw, null);
    }

    private static List<String> parseInList(String column, String raw) {
        if (raw.length() < 2 || raw.charAt(0) != '(' || raw.charAt(raw.length() - 1) != ')') {
            throw DataApiException.badRequest("in 语法须为 in.(a,b,c): " + column);
        }
        String inner = raw.substring(1, raw.length() - 1);
        if (inner.isEmpty()) {
            throw DataApiException.badRequest("in 列表不允许为空: " + column);
        }
        if (inner.indexOf('"') >= 0 || inner.indexOf('\'') >= 0) {
            throw DataApiException.badRequest("in 值不支持引号转义: " + column);
        }
        String[] parts = inner.split(",", -1);
        List<String> values = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (part.isEmpty()) {
                throw DataApiException.badRequest("in 值无法唯一切分(存在空元素或内嵌逗号): " + column);
            }
            values.add(part);
        }
        return List.copyOf(values);
    }

    private static List<String> parseSelect(String value) {
        String[] parts = value.split(",", -1);
        List<String> columns = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (part.isBlank()) {
                throw DataApiException.badRequest("select 含空列名");
            }
            columns.add(part.trim());
        }
        return List.copyOf(columns);
    }

    private static List<OrderTerm> parseOrder(String value) {
        String[] parts = value.split(",", -1);
        List<OrderTerm> terms = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (part.isBlank()) {
                throw DataApiException.badRequest("order 含空列名");
            }
            String column = part.trim();
            boolean asc = true;
            if (column.endsWith(".desc")) {
                column = column.substring(0, column.length() - 5);
                asc = false;
            } else if (column.endsWith(".asc")) {
                column = column.substring(0, column.length() - 4);
            }
            if (column.isBlank()) {
                throw DataApiException.badRequest("order 含空列名");
            }
            terms.add(new OrderTerm(column, asc));
        }
        return List.copyOf(terms);
    }

    private static int parseBoundedInt(String value, int min, int max, String name) {
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw DataApiException.badRequest(name + " 必须为整数");
        }
        if (parsed < min || parsed > max) {
            throw DataApiException.badRequest(name + " 超出允许范围 [" + min + ", " + max + "]");
        }
        return parsed;
    }

}
