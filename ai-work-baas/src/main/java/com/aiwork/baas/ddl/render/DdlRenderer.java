package com.aiwork.baas.ddl.render;

import com.aiwork.baas.ddl.index.IndexNameAllocator;
import com.aiwork.baas.ddl.inspect.ActualIndexName;
import com.aiwork.baas.ddl.type.ColumnType;
import com.aiwork.baas.ddl.type.ColumnTypeValidator;
import com.aiwork.baas.ddl.type.DefaultValueRenderer;
import com.aiwork.baas.ddl.type.LogicalColumn;
import com.aiwork.baas.exception.BaasBadRequestException;
import com.aiwork.baas.provision.IdentifierValidator;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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

    public static final class ColumnPlan {

        private final LogicalColumn column;

        private final DefaultValueRenderer.Rendered defaultValue;

        private ColumnPlan(LogicalColumn column, DefaultValueRenderer.Rendered defaultValue) {
            this.column = column;
            this.defaultValue = defaultValue;
        }

        public LogicalColumn column() {
            return column;
        }

        public String defaultValueSql() {
            return defaultValue == null ? null : defaultValue.ddlLiteral();
        }
    }

    public record RenderedDdl(String sql, String sanitizedSql) {
    }

    /** 类型化 ALTER 子句，仅能由安全工厂创建。 */
    public static final class AlterClause {

        private final String sql;

        private final String sanitizedSql;

        private AlterClause(String sql, String sanitizedSql) {
            this.sql = sql;
            this.sanitizedSql = sanitizedSql;
        }

        public static AlterClause addColumn(ColumnPlan plan) {
            return new AlterClause("ADD COLUMN " + columnDefinitionSql(plan, false),
                    "ADD COLUMN " + columnDefinitionSql(plan, true));
        }

        public static AlterClause dropColumn(String columnName) {
            String column = quoteIdentifier(columnName);
            return new AlterClause("DROP COLUMN " + column, "DROP COLUMN " + column);
        }

        public static AlterClause modifyColumn(ColumnPlan plan) {
            return new AlterClause("MODIFY COLUMN " + columnDefinitionSql(plan, false),
                    "MODIFY COLUMN " + columnDefinitionSql(plan, true));
        }

        public static AlterClause renameColumn(String oldColumnName, String newColumnName) {
            String oldColumn = quoteIdentifier(oldColumnName);
            String newColumn = quoteIdentifier(newColumnName);
            String clause = "RENAME COLUMN " + oldColumn + " TO " + newColumn;
            return new AlterClause(clause, clause);
        }

        public static AlterClause addIndex(boolean unique, String indexName, String columnName) {
            String index = quoteIdentifier(indexName);
            String column = quoteIdentifier(columnName);
            String clause = (unique ? "ADD UNIQUE INDEX " : "ADD INDEX ") + index + " (" + column + ")";
            return new AlterClause(clause, clause);
        }

        public static AlterClause dropIndex(ActualIndexName actualIndexName) {
            String index = actualIndexName.quotedForDdl();
            return new AlterClause("DROP INDEX " + index, "DROP INDEX " + index);
        }

        public static AlterClause renameIndex(ActualIndexName oldIndexName, String newIndexName) {
            String oldIndex = oldIndexName.quotedForDdl();
            String newIndex = quoteIdentifier(newIndexName);
            String clause = "RENAME INDEX " + oldIndex + " TO " + newIndex;
            return new AlterClause(clause, clause);
        }

        public static AlterClause tableComment(String comment) {
            requireLength(comment, TABLE_COMMENT_LIMIT, "表注释超长(≤2048 字符)");
            return new AlterClause("COMMENT='" + DefaultValueRenderer.escapeStringLiteral(comment) + "'", "COMMENT=?");
        }

        public static AlterClause renameTable(String dbName, String tableName) {
            String clause = "RENAME TO " + quoteIdentifier(dbName) + "." + quoteIdentifier(tableName);
            return new AlterClause(clause, clause);
        }

        private String sql() {
            return sql;
        }

        private String sanitizedSql() {
            return sanitizedSql;
        }
    }

    private DdlRenderer() {
    }

    /**
     * 创建由真实 JSON 默认值驱动的列计划，禁止伪造 Rendered 字面量绕过类型校验。
     * @param column 已规范化的逻辑列
     * @param rawDefaultValue API 输入中的 JSON 默认值
     * @return 可安全渲染的列计划
     */
    public static ColumnPlan columnPlan(LogicalColumn column, JsonNode rawDefaultValue) {
        validateColumn(column);
        DefaultValueRenderer.Rendered rendered = DefaultValueRenderer.render(column.type(), column.length(),
                column.scale(), rawDefaultValue);
        String canonical = rendered == null ? null : rendered.canonical();
        if (!Objects.equals(column.defaultValue(), canonical)) {
            throw new BaasBadRequestException("列默认值与规范化默认值不一致");
        }
        return new ColumnPlan(column, rendered);
    }

    public static String typeSql(ColumnType type, Integer length, Integer scale) {
        ColumnTypeValidator.validateTypeParams(type.code(), length, scale);
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
        validateColumn(column);
        StringBuilder sql = new StringBuilder(quoteIdentifier(column.columnName())).append(" ")
            .append(typeSql(column.type(), column.length(), column.scale()))
            .append(column.nullable() ? " NULL" : " NOT NULL");
        if (plan.defaultValue != null) {
            sql.append(" DEFAULT ").append(sanitized ? "?" : plan.defaultValue.ddlLiteral());
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

    private static void validateColumn(LogicalColumn column) {
        IdentifierValidator.validate(column.columnName());
        ColumnTypeValidator.validateTypeParams(column.type().code(), column.length(), column.scale());
    }

    private static String quoteIdentifier(String identifier) {
        IdentifierValidator.validate(identifier);
        return "`" + identifier + "`";
    }

    private static void requireLength(String text, int limit, String message) {
        if (text == null || text.codePointCount(0, text.length()) > limit) {
            throw new BaasBadRequestException(message);
        }
    }

}
