package com.aiwork.baas.ddl.render;

import com.aiwork.baas.ddl.index.IndexNameAllocator;
import com.aiwork.baas.ddl.type.ColumnType;
import com.aiwork.baas.ddl.type.DefaultValueRenderer;
import com.aiwork.baas.ddl.type.LogicalColumn;
import com.aiwork.baas.exception.BaasBadRequestException;
import com.aiwork.baas.provision.IdentifierValidator;

import java.util.ArrayList;
import java.util.List;

/**
 * DDL 渲染器(spec §7.3/§13):客户端字符串一律经转义,默认值只接受 DefaultValueRenderer 产物;
 * sanitizedSql 中默认值以 ? 占位(落 ddl_text)。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public final class DdlRenderer {

    public static final int TABLE_COMMENT_LIMIT = 2048;

    public static final int COLUMN_COMMENT_LIMIT = 1024;

    private static final String PHYSICAL_BASELINE = "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 "
            + "COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC";

    public record ColumnPlan(LogicalColumn column, DefaultValueRenderer.Rendered defaultValue) {
    }

    public record RenderedDdl(String sql, String sanitizedSql) {
    }

    public record AlterClause(String sql, String sanitizedSql) {
    }

    private DdlRenderer() {
    }

    public static String typeSql(ColumnType type, Integer length, Integer scale) {
        return switch (type) {
            case BIGINT -> "bigint";
            case INT -> "int";
            case DECIMAL -> "decimal(" + length + "," + (scale == null ? 0 : scale) + ")";
            case VARCHAR -> "varchar(" + length + ")";
            case TEXT -> "text";
            case BOOLEAN -> "TINYINT(1)";
            case DATE -> "date";
            case DATETIME -> "datetime";
            case JSON -> "json";
        };
    }

    public static String columnDefinitionSql(ColumnPlan plan, boolean sanitized) {
        LogicalColumn column = plan.column();
        IdentifierValidator.validate(column.columnName());
        StringBuilder sql = new StringBuilder("`").append(column.columnName()).append("` ")
            .append(typeSql(column.type(), column.length(), column.scale()))
            .append(column.nullable() ? " NULL" : " NOT NULL");
        if (plan.defaultValue() != null) {
            sql.append(" DEFAULT ").append(sanitized ? "?" : plan.defaultValue().ddlLiteral());
        }
        if (column.comment() != null) {
            requireLength(column.comment(), COLUMN_COMMENT_LIMIT, "列注释超长(≤1024 字符)");
            sql.append(sanitized ? " COMMENT ?" : " COMMENT '"
                    + DefaultValueRenderer.escapeStringLiteral(column.comment()) + "'");
        }
        return sql.toString();
    }

    public static RenderedDdl renderCreateTable(String dbName, String tableName, String tableComment,
            List<ColumnPlan> columns) {
        IdentifierValidator.validate(dbName);
        IdentifierValidator.validate(tableName);
        List<String> realParts = new ArrayList<>();
        List<String> sanitizedParts = new ArrayList<>();
        realParts.add("`id` bigint NOT NULL AUTO_INCREMENT");
        sanitizedParts.add("`id` bigint NOT NULL AUTO_INCREMENT");
        for (ColumnPlan plan : columns) {
            realParts.add(columnDefinitionSql(plan, false));
            sanitizedParts.add(columnDefinitionSql(plan, true));
        }
        List<String> indexParts = new ArrayList<>();
        indexParts.add("PRIMARY KEY (`id`)");
        for (ColumnPlan plan : columns) {
            LogicalColumn column = plan.column();
            if (column.unique()) {
                indexParts.add("UNIQUE KEY `" + IndexNameAllocator.canonicalName(true, column.columnName())
                        + "` (`" + column.columnName() + "`)");
            }
            else if (column.indexed()) {
                indexParts.add("KEY `" + IndexNameAllocator.canonicalName(false, column.columnName()) + "` (`"
                        + column.columnName() + "`)");
            }
        }
        realParts.addAll(indexParts);
        sanitizedParts.addAll(indexParts);

        String commentSuffix = "";
        String sanitizedCommentSuffix = "";
        if (tableComment != null) {
            requireLength(tableComment, TABLE_COMMENT_LIMIT, "表注释超长(≤2048 字符)");
            commentSuffix = " COMMENT='" + DefaultValueRenderer.escapeStringLiteral(tableComment) + "'";
            sanitizedCommentSuffix = " COMMENT=?";
        }
        String head = "CREATE TABLE `" + dbName + "`.`" + tableName + "` (";
        String tail = ") " + PHYSICAL_BASELINE + commentSuffix;
        String sanitizedTail = ") " + PHYSICAL_BASELINE + sanitizedCommentSuffix;
        return new RenderedDdl(head + String.join(", ", realParts) + tail,
                head + String.join(", ", sanitizedParts) + sanitizedTail);
    }

    public static RenderedDdl renderAlterTable(String dbName, String tableName, List<AlterClause> clauses) {
        IdentifierValidator.validate(dbName);
        IdentifierValidator.validate(tableName);
        if (clauses.isEmpty()) {
            throw new BaasBadRequestException("改表请求至少包含一项操作");
        }
        String head = "ALTER TABLE `" + dbName + "`.`" + tableName + "` ";
        return new RenderedDdl(head + String.join(", ", clauses.stream().map(AlterClause::sql).toList()),
                head + String.join(", ", clauses.stream().map(AlterClause::sanitizedSql).toList()));
    }

    private static void requireLength(String text, int limit, String message) {
        if (text.length() > limit) {
            throw new BaasBadRequestException(message);
        }
    }

}
