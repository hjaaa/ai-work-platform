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

package com.aiwork.baas.security;

/**
 * 集成测试当前用户 Provider。
 *
 * @author ai-work
 * @date 2026/07/17
 */
public class TestCurrentUserProvider implements CurrentUserProvider {

    public static volatile Long userId = 1L;

    public static volatile boolean admin;

    @Override
    public Long currentUserId() {
        return userId;
    }

    @Override
    public boolean isBaasAdmin() {
        return admin;
    }

}
