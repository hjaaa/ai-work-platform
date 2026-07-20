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

package com.aiwork.baas.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 改表请求(spec §7.3 操作列表式)，改表意图必须显式列出，不做声明式全量 diff。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public record TableAlterDTO(@NotBlank @Size(max = 64) String operationId, Boolean allowLossy, String newTableName,
        @Size(max = 2048) String comment, @Valid List<ColumnDefinitionDTO> addColumns, List<String> dropColumns,
        @Valid List<ColumnDefinitionDTO> modifyColumns, @Valid List<ColumnRenameDTO> renameColumns) {

    public boolean allowLossyOrDefault() {
        return Boolean.TRUE.equals(allowLossy);
    }

    public List<ColumnDefinitionDTO> addColumnsOrEmpty() {
        return addColumns == null ? List.of() : addColumns;
    }

    public List<String> dropColumnsOrEmpty() {
        return dropColumns == null ? List.of() : dropColumns;
    }

    public List<ColumnDefinitionDTO> modifyColumnsOrEmpty() {
        return modifyColumns == null ? List.of() : modifyColumns;
    }

    public List<ColumnRenameDTO> renameColumnsOrEmpty() {
        return renameColumns == null ? List.of() : renameColumns;
    }

    public boolean hasAnyOperation() {
        return newTableName != null || comment != null || !addColumnsOrEmpty().isEmpty()
                || !dropColumnsOrEmpty().isEmpty() || !modifyColumnsOrEmpty().isEmpty()
                || !renameColumnsOrEmpty().isEmpty();
    }

}
