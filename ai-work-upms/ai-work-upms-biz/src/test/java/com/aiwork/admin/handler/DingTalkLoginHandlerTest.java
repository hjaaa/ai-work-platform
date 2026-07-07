package com.aiwork.admin.handler;

import com.aiwork.admin.api.entity.SysUser;
import com.aiwork.admin.mapper.SysSocialDetailsMapper;
import com.aiwork.admin.service.SysUserService;
import com.aiwork.common.core.exception.CheckedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

	@Test
	void bindRejectsBlankIdentify() {
		// identify 为空时应拒绝绑定并抛业务异常,而非静默返回成功
		DingTalkLoginHandler blankGuardHandler = new DingTalkLoginHandler(sysUserService, sysSocialDetailsMapper) {
			@Override
			protected CheckedException dingTalkBindFailed() {
				return new CheckedException("dingtalk bind failed");
			}
		};
		SysUser user = new SysUser();
		user.setUserId(1L);

		assertThrows(CheckedException.class, () -> blankGuardHandler.bind(user, null));
		verify(sysUserService, never()).updateById(any(SysUser.class));
	}

	@Test
	void bindUpdatesUserWhenIdentifyPresent() {
		SysUser user = new SysUser();

		assertTrue(handler.bind(user, "test-open-id"));

		assertEquals("test-open-id", user.getWxDingUserid());
		verify(sysUserService).updateById(user);
	}

}
