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

package com.aiwork.baas.entity.enums;

/**
 * BaaS 项目状态。
 *
 * @author ai-work
 * @date 2026/07/17
 */
public enum ProjectStatus {

    /** 开通中。 */
    PROVISIONING,
    /** 已启用。 */
    ACTIVE,
    /** 系统表结构迁移中(数据面阻断,spec §9.1)。 */
    MIGRATING,
    /** 开通失败。 */
    FAILED,
    /** 删除中。 */
    DELETING,
    /** 已删除。 */
    DELETED

}
