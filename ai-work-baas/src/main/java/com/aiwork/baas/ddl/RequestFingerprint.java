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

package com.aiwork.baas.ddl;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

/**
 * 操作指纹(spec §9.2):HTTP = SHA-256(方法+服务内路径+操作类型+规范化 body);
 * 内部操作 = 带版本行式载荷。规范化 body:字段按字母序、Map 按 key 序、忽略 null。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public final class RequestFingerprint {

    private static final ObjectMapper CANONICAL_MAPPER = JsonMapper.builder()
        .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
        .serializationInclusion(JsonInclude.Include.NON_NULL)
        .build();

    private RequestFingerprint() {
    }

    public static String canonicalBody(Object body) {
        if (body == null) {
            return "";
        }
        try {
            JsonNode tree = CANONICAL_MAPPER.valueToTree(body);
            pruneNullObjectFields(tree);
            return CANONICAL_MAPPER.writeValueAsString(tree);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("canonical body serialization failed", exception);
        }
    }

    private static void pruneNullObjectFields(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.properties().forEach(entry -> {
                JsonNode child = entry.getValue();
                if (!child.isNull()) {
                    pruneNullObjectFields(child);
                }
            });
            var fields = object.properties().iterator();
            while (fields.hasNext()) {
                if (fields.next().getValue().isNull()) {
                    fields.remove();
                }
            }
        }
        else if (node instanceof ArrayNode array) {
            array.forEach(RequestFingerprint::pruneNullObjectFields);
        }
    }

    public static String http(String method, String servicePath, String operationTypeCode, String canonicalBody) {
        return sha256Hex(method + "\n" + servicePath + "\n" + operationTypeCode + "\n" + canonicalBody);
    }

    public static String cleanupDrop(Long projectId, Long tableId, LocalDateTime deleteAfter) {
        String timestamp = deleteAfter.truncatedTo(ChronoUnit.SECONDS)
            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        return sha256Hex("v1\nkind=cleanup-drop\nprojectId=" + projectId + "\ntableId=" + tableId + "\ndeleteAfter="
                + timestamp + "\n");
    }

    public static String scheduledReconcile(Long projectId, String operationId) {
        return sha256Hex(
                "v1\nkind=reconcile\nprojectId=" + projectId + "\noperationId=" + operationId + "\ntrigger=scheduled\n");
    }

    public static String sha256Hex(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("sha-256 unavailable", exception);
        }
    }

}
