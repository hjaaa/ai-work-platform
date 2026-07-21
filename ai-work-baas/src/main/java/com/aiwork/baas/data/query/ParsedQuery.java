package com.aiwork.baas.data.query;

import java.util.List;

/**
 * 解析后的查询;select 为 null 表示全列。
 *
 * @author ai-work
 * @date 2026/07/21
 */
public record ParsedQuery(List<FilterCondition> filters, List<String> select, List<OrderTerm> order, int limit,
        int offset) {
}
