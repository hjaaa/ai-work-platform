/*
 *
 *      Copyright (c) 2018-2026, lengleng All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice,
 *  this list of conditions and the following disclaimer.
 *  Redistributions in binary form must reproduce the above copyright
 *  notice, this list of conditions and the following disclaimer in the
 *  documentation and/or other materials provided with the distribution.
 *  Neither the name of the pig4cloud.com developer nor the names of its
 *  contributors may be used to endorse or promote products derived from
 *  this software without specific prior written permission.
 *  Author: lengleng (wangiegie@gmail.com)
 *
 */

package com.aiwork.baas.controller.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BaaS 默认值请求绑定精度测试。
 *
 * @author ai-work
 * @date 2026/07/20
 */
class PreciseJsonNodeDeserializerTest {

    @Test
    void decimalTokenRetainsAllDigitsDuringDtoBinding() throws Exception {
        String decimal = "1234567890123456789012345678.12";
        ColumnDefinitionDTO dto = new ObjectMapper().readValue("{\"columnName\":\"amount\","
                + "\"dataType\":\"decimal\",\"length\":30,\"scale\":2,\"defaultValue\":" + decimal + "}",
                ColumnDefinitionDTO.class);

        assertThat(dto.defaultValue().decimalValue().toPlainString()).isEqualTo(decimal);
    }

}
