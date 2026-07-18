/*
 *
 *      Copyright (c) 2018-2025, lengleng All rights reserved.
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

package com.aiwork.baas.controller.dto;

import com.aiwork.baas.entity.BaasProject;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Studio 项目安全视图，不包含数据库账号、密文、库名和 owner 等内部字段。
 *
 * @author ai-work
 * @date 2026/07/17
 */
public record ProjectVO(String projectRef, String name, String status, List<String> allowedOrigins,
        LocalDateTime createTime) {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static ProjectVO from(BaasProject project) {
        return new ProjectVO(project.getProjectRef(), project.getName(), project.getStatus().name(),
                parseOrigins(project.getAllowedOrigins()), project.getCreateTime());
    }

    private static List<String> parseOrigins(String originsJson) {
        if (originsJson == null || originsJson.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(originsJson, new TypeReference<List<String>>() {
            });
        }
        catch (Exception exception) {
            throw new IllegalStateException("allowed_origins stored value is invalid", exception);
        }
    }

}
