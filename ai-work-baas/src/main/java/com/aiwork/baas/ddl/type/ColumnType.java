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

package com.aiwork.baas.ddl.type;

import com.aiwork.baas.exception.BaasBadRequestException;

/**
 * 类型白名单(spec §13)。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public enum ColumnType {

    /** bigint,一律 signed。 */
    BIGINT("bigint"),
    /** int,一律 signed。 */
    INT("int"),
    /** decimal(p,s)。 */
    DECIMAL("decimal"),
    /** varchar(n),n ≤ 4096。 */
    VARCHAR("varchar"),
    /** text,不支持默认值与索引。 */
    TEXT("text"),
    /** boolean,物理渲染 TINYINT(1)。 */
    BOOLEAN("boolean"),
    /** date,精度 0。 */
    DATE("date"),
    /** datetime,精度 0。 */
    DATETIME("datetime"),
    /** json,不支持默认值与索引。 */
    JSON("json");

    private final String code;

    ColumnType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public boolean supportsDefault() {
        return this != TEXT && this != JSON;
    }

    public boolean indexable() {
        return this != TEXT && this != JSON;
    }

    public boolean hasNoParams() {
        return this != DECIMAL && this != VARCHAR;
    }

    public static ColumnType fromCode(String code) {
        for (ColumnType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new BaasBadRequestException("不支持的列类型: " + code);
    }

}
