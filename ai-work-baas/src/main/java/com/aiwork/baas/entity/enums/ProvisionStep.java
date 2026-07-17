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
 * 项目开通步骤，ordinal 即步骤顺序，供 Task 8 使用。
 *
 * @author ai-work
 * @date 2026/07/17
 */
public enum ProvisionStep {

    /** 初始化。 */
    INIT,
    /** 已创建数据库。 */
    DB_CREATED,
    /** 已创建运行时用户。 */
    USER_CREATED,
    /** 已创建系统表。 */
    SYSTEM_TABLES,
    /** 已创建 JWT 密钥。 */
    JWT_KEY,
    /** 已创建 API Key。 */
    API_KEYS

}
