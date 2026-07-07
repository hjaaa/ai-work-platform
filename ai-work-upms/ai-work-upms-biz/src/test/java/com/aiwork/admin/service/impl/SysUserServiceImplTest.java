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
import org.springframework.cache.CacheManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
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

}
