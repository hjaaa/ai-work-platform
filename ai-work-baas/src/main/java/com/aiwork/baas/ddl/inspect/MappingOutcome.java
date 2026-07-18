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

/**
 * 物理 → 逻辑映射结果:成功携带值,失败携带拒绝原因(用于 CONFLICT/REJECTED_IMPORT 报告)。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public record MappingOutcome<T>(T value, String rejectReason) {

    public boolean ok() {
        return rejectReason == null;
    }

    public static <T> MappingOutcome<T> success(T value) {
        return new MappingOutcome<>(value, null);
    }

    public static <T> MappingOutcome<T> reject(String reason) {
        return new MappingOutcome<>(null, reason);
    }

}
