package com.aiwork.baas.data.query;

import java.util.List;

/**
 * 单个过滤条件;IN 时 inValues 非 null 且 rawValue 为 null,IS 时 rawValue 为 null/not_null 字面。
 *
 * @author ai-work
 * @date 2026/07/21
 */
public record FilterCondition(String column, FilterOperator operator, String rawValue, List<String> inValues) {
}
