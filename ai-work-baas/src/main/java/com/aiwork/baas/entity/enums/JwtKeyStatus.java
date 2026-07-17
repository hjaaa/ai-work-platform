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
 * JWT 签名密钥状态。
 *
 * @author ai-work
 * @date 2026/07/17
 */
public enum JwtKeyStatus {

    /** 当前生效密钥。 */
    CURRENT,
    /** 轮换后的保留密钥。 */
    PREVIOUS,
    /** 已撤销密钥。 */
    REVOKED

}
