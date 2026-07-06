package com.aiwork.auth.endpoint;

import com.aiwork.auth.support.core.AuthCaptchaSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证码预检接口测试
 *
 * @author ai-work
 * @date 2026-07-05
 */
class ImageCodeEndpointTest {

	@Test
	void requiredReturnsFailureTimesJudgement() {
		AuthCaptchaSupport captchaSupport = mock(AuthCaptchaSupport.class);
		when(captchaSupport.isFailureTimesReached("admin")).thenReturn(true);
		when(captchaSupport.isFailureTimesReached("newbie")).thenReturn(false);

		ImageCodeEndpoint endpoint = new ImageCodeEndpoint(captchaSupport);

		assertEquals(Boolean.TRUE, endpoint.required("admin").getData());
		assertEquals(Boolean.FALSE, endpoint.required("newbie").getData());
	}

}
