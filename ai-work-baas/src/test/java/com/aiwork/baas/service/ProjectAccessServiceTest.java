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

package com.aiwork.baas.service;

import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.exception.ProjectNotFoundException;
import com.aiwork.baas.mapper.BaasProjectMapper;
import com.aiwork.baas.security.CurrentUserProvider;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class ProjectAccessServiceTest {

    private BaasProjectMapper projectMapper;

    private CurrentUserProvider userProvider;

    private ProjectAccessService accessService;

    private static BaasProject project(long ownerUserId) {
        BaasProject project = new BaasProject();
        project.setId(10L);
        project.setProjectRef("abcdefghijabcdefghij");
        project.setOwnerUserId(ownerUserId);
        return project;
    }

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                BaasProject.class);
        projectMapper = Mockito.mock(BaasProjectMapper.class);
        userProvider = Mockito.mock(CurrentUserProvider.class);
        when(userProvider.currentUserId()).thenReturn(1L);
        accessService = new ProjectAccessService(projectMapper, userProvider);
    }

    @Test
    void ownProjectIsReturned() {
        when(projectMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(project(1L));

        assertThat(accessService.requireOwned("abcdefghijabcdefghij").getId()).isEqualTo(10L);
    }

    @Test
    void foreignProjectThrowsNotFound() {
        when(projectMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(project(2L));
        when(userProvider.isBaasAdmin()).thenReturn(false);

        assertThatThrownBy(() -> accessService.requireOwned("abcdefghijabcdefghij"))
            .isInstanceOf(ProjectNotFoundException.class);
    }

    @Test
    void adminCanAccessForeignProject() {
        when(projectMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(project(2L));
        when(userProvider.isBaasAdmin()).thenReturn(true);

        assertThat(accessService.requireOwned("abcdefghijabcdefghij").getOwnerUserId()).isEqualTo(2L);
    }

    @Test
    void missingProjectThrowsNotFound() {
        when(projectMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> accessService.requireOwned("nosuchref00000000000"))
            .isInstanceOf(ProjectNotFoundException.class);
    }

    @Test
    void listVisibleFiltersByCurrentOwner() {
        when(projectMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(project(1L)));

        assertThat(accessService.listVisible()).hasSize(1);

        assertOwnerFilter(1L);
    }

    @Test
    void listVisibleStillFiltersByCurrentOwnerForAdmin() {
        when(userProvider.isBaasAdmin()).thenReturn(true);
        when(projectMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(project(1L)));

        assertThat(accessService.listVisible()).hasSize(1);

        assertOwnerFilter(1L);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void assertOwnerFilter(Long expectedOwnerUserId) {
        ArgumentCaptor<LambdaQueryWrapper> wrapperCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        Mockito.verify(projectMapper).selectList(wrapperCaptor.capture());
        LambdaQueryWrapper<?> wrapper = wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("owner_user_id");
        assertThat(wrapper.getParamNameValuePairs()).containsValue(expectedOwnerUserId);
    }

}
