package com.aiwork.baas.data.sql;

import com.aiwork.baas.entity.BaasColumn;

import java.util.List;

/**
 * SELECT 计划:SQL + 输出列(与结果集列序对位,供流式序列化)。
 *
 * @author ai-work
 * @date 2026/07/21
 */
public record SelectPlan(BoundSql boundSql, List<BaasColumn> outputColumns) {
}
