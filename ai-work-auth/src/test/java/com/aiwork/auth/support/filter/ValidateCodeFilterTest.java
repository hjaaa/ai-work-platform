package com.aiwork.auth.support.filter;

import com.aiwork.auth.support.core.AuthCaptchaSupport;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自适应验证码过滤器测试
 *
 * @author ai-work
 * @date 2026-07-05
 */
class ValidateCodeFilterTest {

	private final AuthCaptchaSupport captchaSupport = mock(AuthCaptchaSupport.class);

	private final ValidateCodeFilter filter = new ValidateCodeFilter(captchaSupport);

	private MockHttpServletRequest tokenRequest(String grantType) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/token");
		request.setServletPath("/oauth2/token");
		request.setParameter("grant_type", grantType);
		return request;
	}

	@Test
	void passwordGrantBelowThresholdSkipsCaptcha() throws Exception {
		when(captchaSupport.isCaptchaEnabled(any())).thenReturn(true);
		when(captchaSupport.isFailureTimesReached("admin")).thenReturn(false);

		MockHttpServletRequest request = tokenRequest("password");
		request.setParameter("username", "admin");
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, new MockHttpServletResponse(), chain);

		verify(captchaSupport, never()).validateCode(any());
		assertNotNull(chain.getRequest());
	}

	@Test
	void passwordGrantReachedThresholdValidatesCaptcha() throws Exception {
		when(captchaSupport.isCaptchaEnabled(any())).thenReturn(true);
		when(captchaSupport.isFailureTimesReached("admin")).thenReturn(true);

		MockHttpServletRequest request = tokenRequest("password");
		request.setParameter("username", "admin");
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, new MockHttpServletResponse(), chain);

		verify(captchaSupport).validateCode(any());
		assertNotNull(chain.getRequest());
	}

	@Test
	void smsGrantAlwaysValidatesRegardlessOfFailureTimes() throws Exception {
		when(captchaSupport.isCaptchaEnabled(any())).thenReturn(true);

		MockHttpServletRequest request = tokenRequest("mobile");
		request.setParameter("mobile", "SMS@13800138000");
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, new MockHttpServletResponse(), chain);

		// 短信模式不走失败次数判断,验证码(短信码)必校验
		verify(captchaSupport, never()).isFailureTimesReached(anyString());
		verify(captchaSupport).validateCode(any());
	}

	@Test
	void clientCaptchaDisabledSkipsEverything() throws Exception {
		when(captchaSupport.isCaptchaEnabled(any())).thenReturn(false);

		MockHttpServletRequest request = tokenRequest("password");
		request.setParameter("username", "admin");
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, new MockHttpServletResponse(), chain);

		verify(captchaSupport, never()).validateCode(any());
		verify(captchaSupport, never()).isFailureTimesReached(anyString());
		assertNotNull(chain.getRequest());
	}

	@Test
	void nonTokenUrlPassesThrough() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/code/image");
		request.setServletPath("/code/image");
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request, new MockHttpServletResponse(), chain);

		verify(captchaSupport, never()).validateCode(any());
		assertNotNull(chain.getRequest());
	}

}
