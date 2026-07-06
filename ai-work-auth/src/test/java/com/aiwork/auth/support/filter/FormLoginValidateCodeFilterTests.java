package com.aiwork.auth.support.filter;

import com.aiwork.auth.support.core.AuthCaptchaSupport;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 授权码登录表单验证码过滤器测试
 *
 * @author ai-work
 * @date 2026-07-06
 */
class FormLoginValidateCodeFilterTests {

	private final AuthCaptchaSupport captchaSupport = mock(AuthCaptchaSupport.class);

	private final FormLoginValidateCodeFilter filter = new FormLoginValidateCodeFilter(captchaSupport);

	private MockHttpServletRequest formRequest() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/form");
		request.setServletPath("/oauth2/form");
		request.setParameter("username", "admin");
		return request;
	}

	@Test
	void alwaysValidatesCaptchaRegardlessOfFailureTimes() throws Exception {
		when(captchaSupport.resolveAuthorizationClientId(any(), any(), eq(true))).thenReturn("ai-work");
		when(captchaSupport.isCaptchaEnabled("ai-work")).thenReturn(true);

		MockFilterChain chain = new MockFilterChain();
		filter.doFilter(formRequest(), new MockHttpServletResponse(), chain);

		// 表单登录失败不累计 login_error_times，不能按失败次数自适应跳过校验
		verify(captchaSupport, never()).isFailureTimesReached(anyString());
		verify(captchaSupport).validateCode(any());
		assertNotNull(chain.getRequest());
	}

	@Test
	void clientCaptchaDisabledSkipsValidation() throws Exception {
		when(captchaSupport.resolveAuthorizationClientId(any(), any(), eq(true))).thenReturn("ai-work");
		when(captchaSupport.isCaptchaEnabled("ai-work")).thenReturn(false);

		MockFilterChain chain = new MockFilterChain();
		filter.doFilter(formRequest(), new MockHttpServletResponse(), chain);

		verify(captchaSupport, never()).validateCode(any());
		assertNotNull(chain.getRequest());
	}

	@Test
	void nonFormLoginUrlPassesThrough() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/token");
		request.setServletPath("/oauth2/token");

		MockFilterChain chain = new MockFilterChain();
		filter.doFilter(request, new MockHttpServletResponse(), chain);

		verify(captchaSupport, never()).validateCode(any());
		assertNotNull(chain.getRequest());
	}

}
