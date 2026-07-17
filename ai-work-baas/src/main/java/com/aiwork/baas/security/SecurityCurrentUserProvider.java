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

import com.aiwork.common.security.util.SecurityUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 基于 Spring Security 上下文的当前用户 Provider。
 *
 * @author ai-work
 * @date 2026/07/17
 */
@Component
public class SecurityCurrentUserProvider implements CurrentUserProvider {

    private static final String BAAS_ADMIN_AUTHORITY = "baas_admin";

    @Override
    public Long currentUserId() {
        return SecurityUtils.getUser().getId();
    }

    @Override
    public boolean isBaasAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities()
            .stream()
            .anyMatch(authority -> BAAS_ADMIN_AUTHORITY.equals(authority.getAuthority()));
    }

}
