package com.aiwork.admin.handler;

import cn.hutool.json.JSONUtil;
import com.aiwork.admin.api.dto.UserDTO;
import com.aiwork.admin.api.entity.SysUser;
import com.aiwork.admin.api.entity.SysUserSocial;
import com.aiwork.admin.mapper.SysSocialDetailsMapper;
import com.aiwork.admin.mapper.SysUserSocialMapper;
import com.aiwork.admin.service.SysUserService;
import com.aiwork.common.core.exception.CheckedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 飞书登录处理器测试（identify 的 HTTP 链路属集成范畴，联调覆盖）
 *
 * @author ai-work
 * @date 2026-07-07
 */
@ExtendWith(MockitoExtension.class)
class FeishuLoginHandlerTest {

	@Mock
	private SysUserService sysUserService;

	@Mock
	private SysUserSocialMapper sysUserSocialMapper;

	@Mock
	private SysSocialDetailsMapper sysSocialDetailsMapper;

	private FeishuLoginHandler newHandler() {
		return new FeishuLoginHandler(sysUserService, sysUserSocialMapper, sysSocialDetailsMapper) {
			@Override
			protected CheckedException bindFailed() {
				return new CheckedException("feishu bind failed");
			}

			@Override
			protected CheckedException socialAlreadyBound() {
				return new CheckedException("social already bound");
			}
		};
	}

	@Test
	void identifyReturnsNullWhenSocialDetailsMissing() {
		// sys_social_details 未配置 FEISHU 凭证时应友好失败,而非 NPE
		when(sysSocialDetailsMapper.selectOne(any())).thenReturn(null);

		assertNull(newHandler().identify("test-code"));
	}

	@Test
	void infoReturnsNullWhenIdentifyBlank() {
		assertNull(newHandler().info(""));
		verify(sysUserService, never()).getUserInfo(any(UserDTO.class));
	}

	@Test
	void infoReturnsNullWhenBindingMissing() {
		when(sysUserSocialMapper.selectOne(any())).thenReturn(null);

		assertNull(newHandler().info("feishu-open-id"));
		verify(sysUserService, never()).getUserInfo(any(UserDTO.class));
	}

	@Test
	void bindRejectsBlankIdentify() {
		SysUser user = new SysUser();
		user.setUserId(1L);

		assertThrows(CheckedException.class, () -> newHandler().bind(user, ""));
		verify(sysUserSocialMapper, never()).insert(any(SysUserSocial.class));
	}

	@Test
	void bindRejectsIdentifyBoundToAnotherUser() {
		SysUserSocial other = new SysUserSocial();
		other.setUserId(2L);
		when(sysUserSocialMapper.selectOne(any())).thenReturn(other);
		SysUser user = new SysUser();
		user.setUserId(1L);

		assertThrows(CheckedException.class, () -> newHandler().bind(user, "feishu-open-id"));
		verify(sysUserSocialMapper, never()).insert(any(SysUserSocial.class));
	}

	@Test
	void tokenLogSummaryMasksSensitiveFields() {
		String summary = FeishuLoginHandler.buildTokenLogSummary(JSONUtil.parseObj("""
			{
				"code": 0,
				"access_token": "u-test-token",
				"open_id": "ou_test_open_id"
			}
			"""));

		org.junit.jupiter.api.Assertions.assertAll(
				() -> org.junit.jupiter.api.Assertions.assertTrue(summary.contains("code=0")),
				() -> org.junit.jupiter.api.Assertions.assertTrue(summary.contains("accessTokenPresent=true")),
				() -> org.junit.jupiter.api.Assertions.assertFalse(summary.contains("u-test-token")),
				() -> org.junit.jupiter.api.Assertions.assertFalse(summary.contains("ou_test_open_id")),
				() -> org.junit.jupiter.api.Assertions.assertFalse(summary.contains("access_token")),
				() -> org.junit.jupiter.api.Assertions.assertFalse(summary.contains("open_id")));
	}

	@Test
	void userInfoLogSummaryMasksSensitiveFields() {
		String summary = FeishuLoginHandler.buildUserInfoLogSummary(JSONUtil.parseObj("""
			{
				"code": 0,
				"data": {
					"open_id": "ou_test_open_id"
				}
			}
			"""));

		org.junit.jupiter.api.Assertions.assertAll(
				() -> org.junit.jupiter.api.Assertions.assertTrue(summary.contains("code=0")),
				() -> org.junit.jupiter.api.Assertions.assertTrue(summary.contains("openIdPresent=true")),
				() -> org.junit.jupiter.api.Assertions.assertFalse(summary.contains("ou_test_open_id")),
				() -> org.junit.jupiter.api.Assertions.assertFalse(summary.contains("open_id")));
	}

}
