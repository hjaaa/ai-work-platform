package com.aiwork.baas.data.sql;

import com.aiwork.baas.data.bind.ValueCodec;
import com.aiwork.baas.data.bind.WriteBodyParser;
import com.aiwork.baas.data.context.DataRequestContext;
import com.aiwork.baas.data.context.DataRole;
import com.aiwork.baas.data.error.DataApiException;
import com.aiwork.baas.data.meta.TableMeta;
import com.aiwork.baas.data.query.FilterCondition;
import com.aiwork.baas.data.query.FilterOperator;
import com.aiwork.baas.data.query.OrderTerm;
import com.aiwork.baas.data.query.ParsedQuery;
import com.aiwork.baas.ddl.type.ColumnType;
import com.aiwork.baas.entity.BaasColumn;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringJoiner;

/**
 * 参数化 SQL 构建(spec §7.5 两级构建的第二级):列名过元数据允许列表后反引号拼接,
 * 值全部占位符;
 * owner 策略(§8.3)在此注入。
 *
 * @author ai-work
 * @date 2026/07/21
 */
@Component
public class SqlBuilder {

    private final ValueCodec codec;

    public SqlBuilder(ValueCodec codec) {
        this.codec = codec;
    }

    /**
     * 构建带输出列描述的 SELECT。
     *
     * @param meta 表元数据
     * @param query 解析后的查询
     * @param ctx 数据面请求上下文
     * @return SELECT 计划
     */
    public SelectPlan buildSelect(TableMeta meta, ParsedQuery query, DataRequestContext ctx) {
        List<BaasColumn> outputColumns = resolveSelect(meta, query.select());
        StringBuilder sql = new StringBuilder("SELECT ").append(columnList(meta, outputColumns))
            .append(" FROM ")
            .append(quote(meta.table().getTableName()));
        List<Object> params = new ArrayList<>();
        appendWhere(sql, params, meta, query.filters(), ctx, false);
        appendOrder(sql, meta, query.order());
        sql.append(" LIMIT ? OFFSET ?");
        params.add(query.limit());
        params.add(query.offset());
        return new SelectPlan(new BoundSql(sql.toString(), immutableParams(params)), outputColumns);
    }

    /**
     * 构建与 SELECT 使用相同过滤条件的计数 SQL。
     *
     * @param meta 表元数据
     * @param query 解析后的查询
     * @param ctx 数据面请求上下文
     * @return 参数化计数 SQL
     */
    public BoundSql buildCount(TableMeta meta, ParsedQuery query, DataRequestContext ctx) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ").append(quote(meta.table().getTableName()));
        List<Object> params = new ArrayList<>();
        appendWhere(sql, params, meta, query.filters(), ctx, false);
        return new BoundSql(sql.toString(), immutableParams(params));
    }

    /**
     * 构建单行或多行 INSERT。
     *
     * @param meta 表元数据
     * @param write 解析后的写入值
     * @return 参数化 INSERT SQL
     */
    public BoundSql buildInsert(TableMeta meta, WriteBodyParser.ParsedWrite write) {
        StringJoiner columns = new StringJoiner(", ", "(", ")");
        write.columns().forEach(column -> columns.add(quote(requireColumn(meta, column).getColumnName())));
        StringJoiner rowPlaceholder = new StringJoiner(", ", "(", ")");
        write.columns().forEach(column -> rowPlaceholder.add("?"));
        StringJoiner values = new StringJoiner(", ");
        List<Object> params = new ArrayList<>();
        for (List<Object> row : write.rows()) {
            values.add(rowPlaceholder.toString());
            params.addAll(row);
        }
        String sql = "INSERT INTO " + quote(meta.table().getTableName()) + " " + columns + " VALUES " + values;
        return new BoundSql(sql, immutableParams(params));
    }

    /**
     * 构建直接 UPDATE，并注入 owner 过滤条件。
     *
     * @param meta 表元数据
     * @param query 解析后的查询
     * @param write 解析后的写入值
     * @param ctx 数据面请求上下文
     * @return 参数化 UPDATE SQL
     */
    public BoundSql buildUpdate(TableMeta meta, ParsedQuery query, WriteBodyParser.ParsedWrite write,
            DataRequestContext ctx) {
        requireFilters(query);
        StringBuilder sql = new StringBuilder("UPDATE ").append(quote(meta.table().getTableName())).append(" SET ");
        List<Object> params = new ArrayList<>();
        appendSetClause(sql, params, meta, write);
        appendWhere(sql, params, meta, query.filters(), ctx, false);
        return new BoundSql(sql.toString(), immutableParams(params));
    }

    /**
     * 构建 DELETE，并注入 owner 过滤条件。
     *
     * @param meta 表元数据
     * @param query 解析后的查询
     * @param ctx 数据面请求上下文
     * @return 参数化 DELETE SQL
     */
    public BoundSql buildDelete(TableMeta meta, ParsedQuery query, DataRequestContext ctx) {
        requireFilters(query);
        StringBuilder sql = new StringBuilder("DELETE FROM ").append(quote(meta.table().getTableName()));
        List<Object> params = new ArrayList<>();
        appendWhere(sql, params, meta, query.filters(), ctx, false);
        return new BoundSql(sql.toString(), immutableParams(params));
    }

    /**
     * 构建写入前锁定目标 ID 的探测 SQL。
     *
     * @param meta 表元数据
     * @param query 解析后的查询
     * @param ctx 数据面请求上下文
     * @param probeLimit 探测上限
     * @return 参数化 SELECT FOR UPDATE SQL
     */
    public BoundSql buildCaptureIds(TableMeta meta, ParsedQuery query, DataRequestContext ctx, int probeLimit) {
        requireFilters(query);
        StringBuilder sql = new StringBuilder("SELECT `id` FROM ").append(quote(meta.table().getTableName()));
        List<Object> params = new ArrayList<>();
        appendWhere(sql, params, meta, query.filters(), ctx, false);
        sql.append(" LIMIT ? FOR UPDATE");
        params.add(probeLimit);
        return new BoundSql(sql.toString(), immutableParams(params));
    }

    /**
     * 构建按已捕获 ID 更新的 SQL。
     *
     * @param meta 表元数据
     * @param write 解析后的写入值
     * @param ids 目标 ID
     * @return 参数化 UPDATE SQL
     */
    public BoundSql buildUpdateByIds(TableMeta meta, WriteBodyParser.ParsedWrite write, List<Long> ids) {
        StringBuilder sql = new StringBuilder("UPDATE ").append(quote(meta.table().getTableName())).append(" SET ");
        List<Object> params = new ArrayList<>();
        appendSetClause(sql, params, meta, write);
        sql.append(" WHERE `id` IN (").append(placeholders(ids.size())).append(")");
        params.addAll(ids);
        return new BoundSql(sql.toString(), immutableParams(params));
    }

    /**
     * 构建按 ID 查询写入结果的 SQL。
     *
     * @param meta 表元数据
     * @param outputColumns 输出列
     * @param ids 目标 ID
     * @return 参数化 SELECT SQL
     */
    public BoundSql buildSelectByIds(TableMeta meta, List<BaasColumn> outputColumns, List<Long> ids) {
        String sql = "SELECT " + columnList(meta, outputColumns) + " FROM " + quote(meta.table().getTableName())
                + " WHERE `id` IN (" + placeholders(ids.size()) + ") ORDER BY `id` ASC";
        return new BoundSql(sql, immutableParams(ids));
    }

    /**
     * 将 select 参数解析为元数据列，缺省时返回全部列。
     *
     * @param meta 表元数据
     * @param select select 列名，null 表示全部列
     * @return 按请求顺序排列的输出列
     */
    public List<BaasColumn> resolveSelect(TableMeta meta, List<String> select) {
        if (select == null) {
            return meta.columns();
        }
        List<BaasColumn> columns = new ArrayList<>(select.size());
        for (String name : select) {
            columns.add(requireColumn(meta, name));
        }
        return List.copyOf(columns);
    }

    private void appendSetClause(StringBuilder sql, List<Object> params, TableMeta meta,
            WriteBodyParser.ParsedWrite write) {
        StringJoiner set = new StringJoiner(", ");
        List<Object> row = write.rows().get(0);
        for (int i = 0; i < write.columns().size(); i++) {
            BaasColumn column = requireColumn(meta, write.columns().get(i));
            set.add(quote(column.getColumnName()) + " = ?");
            params.add(row.get(i));
        }
        sql.append(set);
    }

    private void appendWhere(StringBuilder sql, List<Object> params, TableMeta meta, List<FilterCondition> filters,
            DataRequestContext ctx, boolean skipOwner) {
        List<String> conditions = new ArrayList<>();
        for (FilterCondition filter : filters) {
            BaasColumn column = requireColumn(meta, filter.column());
            conditions.add(renderCondition(column, filter, params));
        }
        if (!skipOwner) {
            String ownerCondition = ownerCondition(meta, ctx, params);
            if (ownerCondition != null) {
                conditions.add(ownerCondition);
            }
        }
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
    }

    private String renderCondition(BaasColumn column, FilterCondition filter, List<Object> params) {
        ColumnType type = ColumnType.fromCode(column.getDataType());
        if (type == ColumnType.JSON && filter.operator() != FilterOperator.IS) {
            throw DataApiException
                .badRequest("json 列仅支持 is.null / is.not_null 过滤: " + column.getColumnName());
        }
        String quoted = quote(column.getColumnName());
        return switch (filter.operator()) {
            case EQ -> bind(quoted + " = ?", column, filter, params);
            case NEQ -> bind(quoted + " <> ?", column, filter, params);
            case GT -> bind(quoted + " > ?", column, filter, params);
            case GTE -> bind(quoted + " >= ?", column, filter, params);
            case LT -> bind(quoted + " < ?", column, filter, params);
            case LTE -> bind(quoted + " <= ?", column, filter, params);
            case LIKE -> bind(quoted + " LIKE ?", column, filter, params);
            case IN -> {
                for (String element : filter.inValues()) {
                    params.add(codec.parseFilterValue(column, FilterOperator.IN, element));
                }
                yield quoted + " IN (" + placeholders(filter.inValues().size()) + ")";
            }
            case IS -> "null".equals(filter.rawValue()) ? quoted + " IS NULL" : quoted + " IS NOT NULL";
        };
    }

    private String bind(String condition, BaasColumn column, FilterCondition filter, List<Object> params) {
        params.add(codec.parseFilterValue(column, filter.operator(), filter.rawValue()));
        return condition;
    }

    private String ownerCondition(TableMeta meta, DataRequestContext ctx, List<Object> params) {
        String ownerColumnName = meta.table().getOwnerColumn();
        if (ownerColumnName == null || ctx.role() == DataRole.SERVICE_ROLE) {
            return null;
        }
        String quoted = quote(requireColumn(meta, ownerColumnName).getColumnName());
        if (ctx.role() == DataRole.AUTHENTICATED) {
            params.add(ctx.endUserId());
            return quoted + " = ?";
        }
        return quoted + " IS NULL";
    }

    private void appendOrder(StringBuilder sql, TableMeta meta, List<OrderTerm> order) {
        if (order.isEmpty()) {
            return;
        }
        StringJoiner joiner = new StringJoiner(", ");
        for (OrderTerm term : order) {
            requireColumn(meta, term.column());
            joiner.add(quote(term.column()) + (term.asc() ? " ASC" : " DESC"));
        }
        sql.append(" ORDER BY ").append(joiner);
    }

    private static void requireFilters(ParsedQuery query) {
        if (query.filters().isEmpty()) {
            throw DataApiException.badRequest("PATCH/DELETE 必须携带至少一个过滤条件");
        }
    }

    private static BaasColumn requireColumn(TableMeta meta, String name) {
        BaasColumn column = meta.columnsByName().get(name);
        if (column == null) {
            throw DataApiException.badRequest("非法列: " + name);
        }
        return column;
    }

    private static String columnList(TableMeta meta, List<BaasColumn> columns) {
        StringJoiner joiner = new StringJoiner(", ");
        columns.forEach(column -> joiner.add(quote(requireColumn(meta, column.getColumnName()).getColumnName())));
        return joiner.toString();
    }

    private static String placeholders(int count) {
        StringJoiner joiner = new StringJoiner(", ");
        for (int i = 0; i < count; i++) {
            joiner.add("?");
        }
        return joiner.toString();
    }

    private static List<Object> immutableParams(List<?> params) {
        List<Object> copiedParams = new ArrayList<>(params.size());
        copiedParams.addAll(params);
        return Collections.unmodifiableList(copiedParams);
    }

    private static String quote(String identifier) {
        return "`" + identifier + "`";
    }

}
