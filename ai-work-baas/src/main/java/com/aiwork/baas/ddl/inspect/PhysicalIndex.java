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
 * information_schema.STATISTICS 聚合出的索引视图(PRIMARY 也在内,消费方自行过滤)。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public record PhysicalIndex(String indexName, boolean unique, List<Part> parts) {

    /**
     * 物理索引的一部分。
     *
     * @author ai-work
     * @date 2026/07/18
     */
    public record Part(String columnName, String expression, Long subPart, String indexType, String visible,
            String collation) {
    }

}
