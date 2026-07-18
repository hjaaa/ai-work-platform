/*
 *
 *      Copyright (c) 2018-2025, lengleng All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 */

package com.aiwork.baas.entity.enums;

/**
 * Schema 操作类型(spec §6.1),operation_type 列存 code。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public enum DdlOperationType {

    /** 建表。 */
    CREATE("create"),
    /** 改表。 */
    ALTER("alter"),
    /** 删表(软删,无项目库 DDL)。 */
    DROP("drop"),
    /** ACL 与 owner 配置。 */
    ACL_CONFIG("acl-config"),
    /** 到期物理 DROP(内部操作)。 */
    CLEANUP_DROP("cleanup-drop"),
    /** 表结构对账(项目级)。 */
    RECONCILE("reconcile");

    private final String code;

    DdlOperationType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static DdlOperationType fromCode(String code) {
        for (DdlOperationType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown ddl operation type: " + code);
    }

}
