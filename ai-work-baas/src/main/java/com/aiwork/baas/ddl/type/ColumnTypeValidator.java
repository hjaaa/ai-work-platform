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

package com.aiwork.baas.ddl.type;

import com.aiwork.baas.exception.BaasBadRequestException;

/**
 * 类型参数矩阵校验(spec §13):纯 DTO 静态校验,可在锁外先行。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public final class ColumnTypeValidator {

    public static final int VARCHAR_MAX_LENGTH = 4096;

    private ColumnTypeValidator() {
    }

    public static ColumnType validateTypeParams(String dataType, Integer length, Integer scale) {
        ColumnType type = ColumnType.fromCode(dataType);
        switch (type) {
            case DECIMAL -> {
                if (length == null || length < 1 || length > 65) {
                    throw new BaasBadRequestException("decimal 精度 p 须满足 1 <= p <= 65");
                }
                int effectiveScale = scale == null ? 0 : scale;
                if (effectiveScale < 0 || effectiveScale > Math.min(30, length)) {
                    throw new BaasBadRequestException("decimal 小数位 s 须满足 0 <= s <= min(30, p)");
                }
            }
            case VARCHAR -> {
                if (length == null || length < 1 || length > VARCHAR_MAX_LENGTH) {
                    throw new BaasBadRequestException("varchar 长度须满足 1 <= n <= " + VARCHAR_MAX_LENGTH);
                }
                if (scale != null) {
                    throw new BaasBadRequestException("varchar 不接受 scale 参数");
                }
            }
            default -> {
                if (length != null || scale != null) {
                    throw new BaasBadRequestException(type.code() + " 不接受 length/scale 参数");
                }
            }
        }
        return type;
    }

    /** decimal 的 scale 归一(null → 0),其余类型原样返回。 */
    public static Integer normalizeScale(ColumnType type, Integer scale) {
        if (type == ColumnType.DECIMAL) {
            return scale == null ? 0 : scale;
        }
        return scale;
    }

}
