package com.aiwork.baas.data.sql;

import java.util.List;

/**
 * 参数化 SQL(值全部占位符,标识符全部允许列表反引号)。
 *
 * @author ai-work
 * @date 2026/07/21
 */
public record BoundSql(String sql, List<Object> params) {
}
