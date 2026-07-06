package com.aiwork.auth.support.handler;

import com.aiwork.admin.api.feign.RemoteUserService;
import com.aiwork.common.data.cache.RedisUtils;
import com.aiwork.common.data.resolver.ParamResolver;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationEventPublisher;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 登录失败计数与锁定逻辑测试
 *
 * @author ai-work
 * @date 2026-07-06
 */
class AiWorkAuthenticationFailureEventHandlerTests {

	private final RemoteUserService userService = mock(RemoteUserService.class);

	private final AiWorkAuthenticationFailureEventHandler handler = new AiWorkAuthenticationFailureEventHandler(
			mock(ApplicationEventPublisher.class), userService);

	@Test
	void lockDisabledStillCountsFailureWithoutLocking() {
		try (MockedStatic<ParamResolver> params = mockStatic(ParamResolver.class);
				MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
			params.when(() -> ParamResolver.getLong("LOGIN_ERROR_TIMES", 5L)).thenReturn(0L);
			params.when(() -> ParamResolver.getLong("DELTA_TIME", 1L)).thenReturn(1L);
			redis.when(() -> RedisUtils.increment(anyString(), eq(1L))).thenReturn(10L);

			handler.recordLoginFailureTimes("admin");

			// 锁定关闭也要累计失败次数，供自适应验证码阈值消费
			redis.verify(() -> RedisUtils.increment(anyString(), eq(1L)));
			verify(userService, never()).lockUser(anyString());
		}
	}

	@Test
	void lockEnabledLocksUserWhenThresholdReached() {
		try (MockedStatic<ParamResolver> params = mockStatic(ParamResolver.class);
				MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
			params.when(() -> ParamResolver.getLong("LOGIN_ERROR_TIMES", 5L)).thenReturn(5L);
			params.when(() -> ParamResolver.getLong("DELTA_TIME", 1L)).thenReturn(1L);
			redis.when(() -> RedisUtils.increment(anyString(), eq(1L))).thenReturn(5L);

			handler.recordLoginFailureTimes("admin");

			verify(userService).lockUser("admin");
		}
	}

	@Test
	void lockEnabledBelowThresholdOnlyCounts() {
		try (MockedStatic<ParamResolver> params = mockStatic(ParamResolver.class);
				MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
			params.when(() -> ParamResolver.getLong("LOGIN_ERROR_TIMES", 5L)).thenReturn(5L);
			params.when(() -> ParamResolver.getLong("DELTA_TIME", 1L)).thenReturn(1L);
			redis.when(() -> RedisUtils.increment(anyString(), eq(1L))).thenReturn(1L);

			handler.recordLoginFailureTimes("admin");

			verify(userService, never()).lockUser(anyString());
		}
	}

}
