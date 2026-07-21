package com.aiwork.baas.data.bind;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 测试辅助:固定返回单个值的 ResultSet 代理,wasNull 语义与 JDBC 一致。
 *
 * @author ai-work
 * @date 2026/07/21
 */
final class SingleValueResultSet {

    private SingleValueResultSet() {
    }

    static ResultSet of(Object value) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "wasNull" -> value == null;
            case "getLong" -> value == null ? 0L : ((Number)value).longValue();
            case "getInt" -> value == null ? 0 : ((Number)value).intValue();
            case "getBigDecimal" -> value == null ? null : (BigDecimal)value;
            case "getBoolean" -> value != null && (Boolean)value;
            case "getString" -> value == null ? null : String.valueOf(value);
            case "getObject" -> {
                if (args != null && args.length == 2 && args[1] == LocalDate.class) {
                    yield value;
                }
                if (args != null && args.length == 2 && args[1] == LocalDateTime.class) {
                    yield value;
                }
                yield value;
            }
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (ResultSet)Proxy.newProxyInstance(SingleValueResultSet.class.getClassLoader(),
                new Class<?>[] { ResultSet.class }, handler);
    }

}
