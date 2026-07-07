package com.aiwork.admin.handler;

import com.aiwork.admin.api.dto.UserDTO;
import com.aiwork.admin.api.dto.UserInfo;
import com.aiwork.admin.api.entity.SysUser;
import com.aiwork.admin.api.entity.SysUserSocial;
import com.aiwork.admin.mapper.SysSocialDetailsMapper;
import com.aiwork.admin.mapper.SysUserSocialMapper;
import com.aiwork.admin.service.SysUserService;
import com.aiwork.common.core.exception.CheckedException;
import com.aiwork.common.core.util.R;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 钉钉登录处理器测试：绑定关系迁移至 sys_user_social 后的行为
 *
 * @author ai-work
 * @date 2026-07-07
 */
@ExtendWith(MockitoExtension.class)
class DingTalkLoginHandlerTest {

	@Mock
	private SysUserService sysUserService;

	@Mock
	private SysUserSocialMapper sysUserSocialMapper;

	@Mock
	private SysSocialDetailsMapper sysSocialDetailsMapper;

	private DingTalkLoginHandler handler;

	@BeforeEach
	void setUp() {
		// 绑定失败/冲突异常内部走 MsgUtils（依赖 Spring 上下文），单测统一用可覆写方法替换
		handler = new DingTalkLoginHandler(sysUserService, sysUserSocialMapper, sysSocialDetailsMapper) {
			@Override
			protected CheckedException bindFailed() {
				return new CheckedException("dingtalk bind failed");
			}

			@Override
			protected CheckedException socialAlreadyBound() {
				return new CheckedException("social already bound");
			}
		};
	}

	@Test
	void identifyReturnsNullWhenSocialDetailsMissing() {
		// sys_social_details 未配置 DINGTALK 凭证时应友好失败,而非 NPE
		when(sysSocialDetailsMapper.selectOne(any())).thenReturn(null);

		assertNull(handler.identify("test-auth-code"));
	}

	@Test
	void infoReturnsNullWhenIdentifyBlank() {
		assertNull(handler.info(""));
		verify(sysUserService, never()).getUserInfo(any(UserDTO.class));
	}

	@Test
	void infoReturnsNullWhenBindingMissing() {
		// 绑定表查不到 → 未绑定,由 mobile grant 统一报未绑定错误
		when(sysUserSocialMapper.selectOne(any())).thenReturn(null);

		assertNull(handler.info("test-open-id"));
		verify(sysUserService, never()).getUserInfo(any(UserDTO.class));
	}

	@Test
	void infoQueriesUserByBoundUserId() {
		SysUserSocial social = new SysUserSocial();
		social.setUserId(42L);
		when(sysUserSocialMapper.selectOne(any())).thenReturn(social);
		UserInfo userInfo = new UserInfo();
		when(sysUserService.getUserInfo(any(UserDTO.class))).thenReturn(R.ok(userInfo));

		assertSame(userInfo, handler.info("test-open-id"));

		ArgumentCaptor<UserDTO> captor = ArgumentCaptor.forClass(UserDTO.class);
		verify(sysUserService).getUserInfo(captor.capture());
		assertEquals(42L, captor.getValue().getUserId());
	}

	@Test
	void bindRejectsBlankIdentify() {
		// identify 为空时应拒绝绑定并抛业务异常,而非静默返回成功
		SysUser user = new SysUser();
		user.setUserId(1L);

		assertThrows(CheckedException.class, () -> handler.bind(user, null));
		verify(sysUserSocialMapper, never()).insert(any(SysUserSocial.class));
	}

	@Test
	void bindRejectsIdentifyBoundToAnotherUser() {
		// 同一个三方账号不允许绑到第二个用户
		SysUserSocial other = new SysUserSocial();
		other.setUserId(2L);
		when(sysUserSocialMapper.selectOne(any())).thenReturn(other);
		SysUser user = new SysUser();
		user.setUserId(1L);

		assertThrows(CheckedException.class, () -> handler.bind(user, "test-open-id"));
		verify(sysUserSocialMapper, never()).insert(any(SysUserSocial.class));
	}

	@Test
	void bindInsertsWhenNoExistingBinding() {
		when(sysUserSocialMapper.selectOne(any())).thenReturn(null);
		SysUser user = new SysUser();
		user.setUserId(1L);

		assertTrue(handler.bind(user, "test-open-id"));

		ArgumentCaptor<SysUserSocial> captor = ArgumentCaptor.forClass(SysUserSocial.class);
		verify(sysUserSocialMapper).insert(captor.capture());
		assertEquals(1L, captor.getValue().getUserId());
		assertEquals("DINGTALK", captor.getValue().getType());
		assertEquals("test-open-id", captor.getValue().getIdentify());
	}

	@Test
	void bindUpdatesIdentifyWhenRebinding() {
		// 同一用户重绑：identify 未被他人占用(第一次查询 null)、该用户已有旧绑定(第二次查询命中) → 更新
		SysUserSocial mine = new SysUserSocial();
		mine.setUserId(1L);
		mine.setIdentify("old-open-id");
		when(sysUserSocialMapper.selectOne(any())).thenReturn(null).thenReturn(mine);
		SysUser user = new SysUser();
		user.setUserId(1L);

		assertTrue(handler.bind(user, "new-open-id"));

		assertEquals("new-open-id", mine.getIdentify());
		verify(sysUserSocialMapper).updateById(mine);
		verify(sysUserSocialMapper, never()).insert(any(SysUserSocial.class));
	}

}
