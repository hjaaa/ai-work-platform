/*
 *
 *      Copyright (c) 2018-2026, lengleng All rights reserved.
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
 * DDL 检查点,只前进不回退(spec §9.2)。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public enum DdlStep {

    /** 校验通过、日志已落。 */
    PREPARED,
    /** 项目库 DDL 已确认生效。 */
    DDL_APPLIED,
    /** 平台元数据已更新(即 SUCCESS)。 */
    METADATA_APPLIED;

    public boolean reached(DdlStep other) {
        return this.ordinal() >= other.ordinal();
    }

}
