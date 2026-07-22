package com.aiwork.baas.data.bind;

import com.aiwork.baas.data.context.DataRequestContext;
import com.aiwork.baas.data.context.DataRole;
import com.aiwork.baas.data.error.DataApiException;
import com.aiwork.baas.data.meta.TableMeta;
import com.aiwork.baas.entity.BaasColumn;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 写请求 body 解析(spec §7.1 NULL/缺失/批量键集合;§8.3 owner 与 id 保护)。
 *
 * @author ai-work
 * @date 2026/07/21
 */
@Component
public class WriteBodyParser {

    private final ValueCodec codec;

    public WriteBodyParser(ValueCodec codec) {
        this.codec = codec;
    }

    /**
     * 解析结果:columns 为 INSERT 列清单(批量所有行一致),rows 每行与 columns 对位,
     * 元素可为 null(SQL NULL)。
     */
    public record ParsedWrite(List<String> columns, List<List<Object>> rows) {
    }

    /**
     * 解析单行或批量 INSERT 请求体，并按角色强制注入 owner。
     *
     * @param meta 表元数据
     * @param body JSON 对象或对象数组
     * @param ctx 数据面请求上下文
     * @param batchMaxRows 批量最大行数
     * @return 对位后的列和值
     */
    public ParsedWrite parseInsert(TableMeta meta, JsonNode body, DataRequestContext ctx, int batchMaxRows) {
        List<JsonNode> rowNodes = new ArrayList<>();
        if (body != null && body.isArray()) {
            if (body.isEmpty()) {
                throw DataApiException.badRequest("批量插入数组不能为空");
            }
            if (body.size() > batchMaxRows) {
                throw DataApiException.badRequest("批量插入行数超过上限 " + batchMaxRows);
            }
            body.forEach(rowNodes::add);
        } else if (body != null && body.isObject()) {
            rowNodes.add(body);
        } else {
            throw DataApiException.badRequest("请求体必须为 JSON 对象或对象数组");
        }

        Set<String> keys = fieldKeys(rowNodes.get(0));
        for (JsonNode rowNode : rowNodes) {
            if (!rowNode.isObject()) {
                throw DataApiException.badRequest("批量数组元素必须为 JSON 对象");
            }
            if (!fieldKeys(rowNode).equals(keys)) {
                throw DataApiException.badRequest("批量数组各行字段集合必须一致");
            }
        }
        validateKeys(meta, keys, ctx.role());

        List<String> columns = new ArrayList<>(keys);
        String ownerColumnName = meta.table().getOwnerColumn();
        boolean injectOwner = ownerColumnName != null && ctx.role() != DataRole.SERVICE_ROLE;
        if (injectOwner) {
            columns.add(ownerColumnName);
        }
        List<List<Object>> rows = new ArrayList<>(rowNodes.size());
        for (JsonNode rowNode : rowNodes) {
            List<Object> row = new ArrayList<>(columns.size());
            for (String key : keys) {
                row.add(bindValue(meta, key, rowNode.get(key)));
            }
            if (injectOwner) {
                if (ctx.role() == DataRole.AUTHENTICATED) {
                    row.add(ctx.endUserId());
                } else {
                    requireNullableOwner(meta, ownerColumnName);
                    row.add(null);
                }
            }
            rows.add(row);
        }
        return new ParsedWrite(List.copyOf(columns), List.copyOf(rows));
    }

    /**
     * 解析 PATCH 请求体。
     *
     * @param meta 表元数据
     * @param body JSON 对象
     * @param ctx 数据面请求上下文
     * @return 单行列和值
     */
    public ParsedWrite parsePatch(TableMeta meta, JsonNode body, DataRequestContext ctx) {
        if (body == null || !body.isObject()) {
            throw DataApiException.badRequest("PATCH 请求体必须为 JSON 对象");
        }
        Set<String> keys = fieldKeys(body);
        if (keys.isEmpty()) {
            throw DataApiException.badRequest("PATCH 请求体至少包含一个字段");
        }
        validateKeys(meta, keys, ctx.role());
        List<Object> row = new ArrayList<>(keys.size());
        for (String key : keys) {
            row.add(bindValue(meta, key, body.get(key)));
        }
        return new ParsedWrite(List.copyOf(keys), List.of(row));
    }

    private Object bindValue(TableMeta meta, String columnName, JsonNode node) {
        BaasColumn column = meta.columnsByName().get(columnName);
        if (node == null || node.isNull()) {
            if (!Boolean.TRUE.equals(column.getNullable())) {
                throw DataApiException.badRequest("列 " + columnName + " 不允许为 NULL");
            }
            return null;
        }
        return codec.parseBodyValue(column, node);
    }

    private static void validateKeys(TableMeta meta, Set<String> keys, DataRole role) {
        String ownerColumnName = meta.table().getOwnerColumn();
        for (String key : keys) {
            if (!meta.columnsByName().containsKey(key)) {
                throw DataApiException.badRequest("未知列: " + key);
            }
            if ("id".equals(key)) {
                throw DataApiException.badRequest("请求体不得包含 id 列(主键由服务端生成)");
            }
            if (role != DataRole.SERVICE_ROLE && key.equals(ownerColumnName)) {
                throw DataApiException.badRequest("请求体不得包含 owner 列: " + key);
            }
        }
    }

    private void requireNullableOwner(TableMeta meta, String ownerColumnName) {
        BaasColumn owner = meta.columnsByName().get(ownerColumnName);
        if (owner == null || !Boolean.TRUE.equals(owner.getNullable())) {
            throw DataApiException.forbidden("owner 列不可空,anon 不允许插入", null);
        }
    }

    private static Set<String> fieldKeys(JsonNode node) {
        Set<String> keys = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

}
