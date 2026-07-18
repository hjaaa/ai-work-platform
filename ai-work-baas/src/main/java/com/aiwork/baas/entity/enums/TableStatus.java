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
 * 表状态机(spec §9.5)。baas_table.status 存 name()。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public enum TableStatus {

    /** 建表中。 */
    CREATING,
    /** 可用。 */
    ACTIVE,
    /** 改表中(数据面阻断)。 */
    ALTERING,
    /** 建表失败。 */
    FAILED,
    /** 结构冲突(保持阻断)。 */
    CONFLICT,
    /** 软删 tombstone。 */
    DELETED

}
