package com.aiwork.admin.service.impl;

import com.aiwork.admin.mapper.SysDeptMapper;
import com.aiwork.admin.mapper.SysUserDeptMapper;
import com.aiwork.admin.mapper.SysUserMapper;
import com.aiwork.admin.mapper.SysUserPostMapper;
import com.aiwork.admin.mapper.SysUserRoleMapper;
import com.aiwork.admin.mapper.SysUserSocialMapper;
import com.aiwork.admin.service.SysDeptService;
import com.aiwork.admin.service.SysMenuService;
import com.aiwork.admin.service.SysPostService;
import com.aiwork.admin.service.SysRoleService;
import com.aiwork.common.core.constant.enums.LoginTypeEnum;
import com.aiwork.common.core.util.R;
import com.aiwork.common.security.service.AiWorkUser;
import com.aiwork.common.security.util.SecurityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import com.aiwork.admin.api.entity.SysUser;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SysUserServiceImplTest {

	@Mock
	private SysMenuService sysMenuService;

	@Mock
	private SysRoleService sysRoleService;

	@Mock
	private SysPostService sysPostService;

	@Mock
	private SysDeptService sysDeptService;

	@Mock
	private SysUserRoleMapper sysUserRoleMapper;

	@Mock
	private SysUserPostMapper sysUserPostMapper;

	@Mock
	private SysUserDeptMapper sysUserDeptMapper;

	@Mock
	private CacheManager cacheManager;

	@Mock
	private Cache cache;

	@Mock
	private SysDeptMapper sysDeptMapper;

	@Mock
	private SysUserMapper sysUserMapper;

	@Mock
	private SysUserSocialMapper sysUserSocialMapper;

	@InjectMocks
	private SysUserServiceImpl sysUserService;

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(sysUserService, "baseMapper", sysUserMapper);
	}

	@Test
	void unbindingDingTalkDeletesSocialBindingWithoutUpdatingLegacyUserField() {
		when(sysUserSocialMapper.delete(any())).thenReturn(0);
		AiWorkUser user = new AiWorkUser(1L, "tester", Collections.emptyList(), null, Collections.emptyList(),
				Collections.emptyList(), null, null, null, null, null, "password", true, true, null, true, null,
				true, Collections.emptyList());

		try (MockedStatic<SecurityUtils> mockedSecurityUtils = mockStatic(SecurityUtils.class)) {
			mockedSecurityUtils.when(SecurityUtils::getUser).thenReturn(user);

			R result = sysUserService.unbinding(LoginTypeEnum.DINGTALK.getType());

			assertTrue(result.isOk());
		}

		verify(sysUserSocialMapper).delete(any());
		verifyNoInteractions(sysUserMapper);
	}

	@Test
	void unbindingFeishuDeletesSocialBinding() {
		// 飞书与钉钉同走 sys_user_social,不能落入「不支持的解绑类型」
		when(sysUserSocialMapper.delete(any())).thenReturn(0);
		AiWorkUser user = new AiWorkUser(1L, "tester", Collections.emptyList(), null, Collections.emptyList(),
				Collections.emptyList(), null, null, null, null, null, "password", true, true, null, true, null,
				true, Collections.emptyList());

		try (MockedStatic<SecurityUtils> mockedSecurityUtils = mockStatic(SecurityUtils.class)) {
			mockedSecurityUtils.when(SecurityUtils::getUser).thenReturn(user);

			R result = sysUserService.unbinding(LoginTypeEnum.FEISHU.getType());

			assertTrue(result.isOk());
		}

		verify(sysUserSocialMapper).delete(any());
		verifyNoInteractions(sysUserMapper);
	}

	@Test
	void deleteUserByIdsRemovesSocialBindings() {
		// 删除用户须一并清理绑定行:唯一索引下残留行会让该三方账号永远无法绑定新用户
		SysUser dbUser = new SysUser();
		dbUser.setUserId(1L);
		dbUser.setUsername("tester");
		when(sysUserMapper.selectByIds(any())).thenReturn(List.of(dbUser));
		when(cacheManager.getCache(any())).thenReturn(cache);
		// removeBatchByIds 依赖 MP 运行时(SqlSession),纯单测环境桩掉,只验证关联清理
		SysUserServiceImpl spyService = spy(sysUserService);
		doReturn(true).when(spyService).removeBatchByIds(anyCollection());

		assertTrue(spyService.deleteUserByIds(new Long[] { 1L }));

		verify(sysUserSocialMapper).delete(any());
	}

}
