package com.aiwork.baas.data.query;

import com.aiwork.baas.data.error.DataApiException;

/**
 * 过滤操作符(spec §7.1:eq/neq/gt/gte/lt/lte/like/in/is)。
 *
 * @author ai-work
 * @date 2026/07/21
 */
public enum FilterOperator {

    /** 等于。 */
    EQ("eq"),
    /** 不等于。 */
    NEQ("neq"),
    /** 大于。 */
    GT("gt"),
    /** 大于等于。 */
    GTE("gte"),
    /** 小于。 */
    LT("lt"),
    /** 小于等于。 */
    LTE("lte"),
    /** 模糊匹配。 */
    LIKE("like"),
    /** 列表匹配。 */
    IN("in"),
    /** 空值匹配。 */
    IS("is");

    private final String token;

    FilterOperator(String token) {
        this.token = token;
    }

    /**
     * 获取查询参数中的操作符标记。
     *
     * @return 操作符标记
     */
    public String token() {
        return token;
    }

    /**
     * 根据查询参数标记获取操作符。
     *
     * @param token 操作符标记
     * @return 对应操作符
     */
    public static FilterOperator fromToken(String token) {
        for (FilterOperator operator : values()) {
            if (operator.token.equals(token)) {
                return operator;
            }
        }
        throw DataApiException.badRequest("不支持的操作符: " + token);
    }

}
