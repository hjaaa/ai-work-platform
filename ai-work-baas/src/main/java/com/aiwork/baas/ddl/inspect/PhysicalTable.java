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

package com.aiwork.baas.ddl.inspect;

import java.util.List;

/**
 * 单表物理结构快照。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public record PhysicalTable(String tableName, String tableType, String engine, String rowFormat, String collation,
        String tableComment, boolean hasTriggers, boolean hasForeignKeys, boolean hasCheckConstraints,
        List<PhysicalColumn> columns, List<PhysicalIndex> indexes) {

    public List<PhysicalIndex> secondaryIndexes() {
        return indexes.stream().filter(index -> !"PRIMARY".equals(index.indexName())).toList();
    }

    public PhysicalColumn findColumn(String columnName) {
        return columns.stream().filter(column -> column.columnName().equals(columnName)).findFirst().orElse(null);
    }

    /**
     * 该列上第一个满足 spec §9.4 单列索引谓词的二级索引,没有返回 null。
     * @return 可映射索引,没有则返回 null
     */
    public PhysicalIndex mappableSingleColumnIndexOn(String columnName) {
        return secondaryIndexes().stream()
            .filter(SchemaInspector::isMappableSingleColumnIndex)
            .filter(index -> index.parts().get(0).columnName().equals(columnName))
            .findFirst()
            .orElse(null);
    }

}
