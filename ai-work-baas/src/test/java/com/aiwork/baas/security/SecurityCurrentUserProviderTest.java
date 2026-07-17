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

import com.aiwork.common.security.service.AiWorkUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityCurrentUserProviderTest {

    private final SecurityCurrentUserProvider userProvider = new SecurityCurrentUserProvider();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void currentUserIdComesFromAuthenticatedAiWorkUser() {
        var authorities = AuthorityUtils.createAuthorityList("studio_read");
        AiWorkUser user = new AiWorkUser(42L, "studio", List.of(), null, List.of(), List.of(), null, null,
                null, null, null, "password", true, true, null, true, null, true, authorities);
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken(user, "password", authorities));

        assertThat(userProvider.currentUserId()).isEqualTo(42L);
    }

    @Test
    void baasAdminUsesExactAuthorityString() {
        var authentication = new UsernamePasswordAuthenticationToken("studio", "password",
                AuthorityUtils.createAuthorityList("baas_admin"));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        assertThat(userProvider.isBaasAdmin()).isTrue();
    }

    @Test
    void roleLikeAuthorityIsNotParsedAsBaasAdmin() {
        var authentication = new UsernamePasswordAuthenticationToken("studio", "password",
                AuthorityUtils.createAuthorityList("ROLE_baas_admin"));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        assertThat(userProvider.isBaasAdmin()).isFalse();
    }

}
