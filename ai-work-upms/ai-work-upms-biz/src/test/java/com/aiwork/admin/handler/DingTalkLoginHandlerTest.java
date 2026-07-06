package com.aiwork.admin.handler;

import com.aiwork.admin.mapper.SysSocialDetailsMapper;
import com.aiwork.admin.service.SysUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 钉钉登录处理器测试
 *
 * @author ai-work
 * @date 2026-07-06
 */
@ExtendWith(MockitoExtension.class)
class DingTalkLoginHandlerTest {

	@Mock
	private SysUserService sysUserService;

	@Mock
	private SysSocialDetailsMapper sysSocialDetailsMapper;

	@InjectMocks
	private DingTalkLoginHandler handler;

	@Test
	void identifyReturnsNullWhenSocialDetailsMissing() {
		// sys_social_details 未配置 DINGTALK 凭证时应友好失败,而非 NPE
		when(sysSocialDetailsMapper.selectOne(any())).thenReturn(null);

		assertNull(handler.identify("test-auth-code"));
	}

}
