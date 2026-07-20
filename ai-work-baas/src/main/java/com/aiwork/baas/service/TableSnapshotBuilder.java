/*
 *
 *      Copyright (c) 2018-2026, lengleng All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 * Neither the name of the pig4cloud.com developer nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 * Author: lengleng (wangiegie@gmail.com)
 *
 */

package com.aiwork.baas.service;

import com.aiwork.baas.entity.BaasColumn;
import com.aiwork.baas.entity.BaasTable;
import com.aiwork.baas.entity.BaasTableAcl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 表详情快照(result_snapshot 与 GET 详情共用同一结构)。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public final class TableSnapshotBuilder {

    private TableSnapshotBuilder() {
    }

    public static ObjectNode build(ObjectMapper mapper, BaasTable table, List<BaasColumn> columns,
            List<BaasTableAcl> acls) {
        ObjectNode root = mapper.createObjectNode();
        root.put("tableName", table.getTableName());
        root.put("comment", table.getComment());
        root.put("status", table.getStatus());
        root.put("ownerColumn", table.getOwnerColumn());
        ArrayNode columnArray = root.putArray("columns");
        for (BaasColumn column : columns) {
            ObjectNode node = columnArray.addObject();
            node.put("columnName", column.getColumnName());
            node.put("dataType", column.getDataType());
            if (column.getLength() != null) {
                node.put("length", column.getLength());
            }
            if (column.getScale() != null) {
                node.put("scale", column.getScale());
            }
            node.put("nullable", Boolean.TRUE.equals(column.getNullable()));
            node.put("defaultValue", column.getDefaultValue());
            node.put("pk", Boolean.TRUE.equals(column.getPk()));
            node.put("autoIncrement", Boolean.TRUE.equals(column.getAutoIncrement()));
            node.put("unique", Boolean.TRUE.equals(column.getUnique()));
            node.put("indexed", Boolean.TRUE.equals(column.getIndexed()));
            node.put("comment", column.getComment());
        }
        ObjectNode aclNode = root.putObject("acl");
        for (String role : List.of("anon", "authenticated")) {
            BaasTableAcl acl = acls.stream().filter(item -> role.equals(item.getRole())).findFirst().orElse(null);
            ObjectNode roleNode = aclNode.putObject(role);
            roleNode.put("select", acl != null && Boolean.TRUE.equals(acl.getCanSelect()));
            roleNode.put("insert", acl != null && Boolean.TRUE.equals(acl.getCanInsert()));
            roleNode.put("update", acl != null && Boolean.TRUE.equals(acl.getCanUpdate()));
            roleNode.put("delete", acl != null && Boolean.TRUE.equals(acl.getCanDelete()));
        }
        return root;
    }

}
