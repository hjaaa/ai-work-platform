package com.aiwork.auth.support.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证码触发阈值判断逻辑测试
 *
 * @author ai-work
 * @date 2026-07-05
 */
class AuthCaptchaSupportTest {

	@Test
	void thresholdDisabledAlwaysRequiresCaptcha() {
		// 阈值为空或 <=0 表示关闭自适应,退回"每次都校验"旧行为
		assertTrue(AuthCaptchaSupport.isCaptchaTriggerReached(null, 0L));
		assertTrue(AuthCaptchaSupport.isCaptchaTriggerReached(0L, null));
		assertTrue(AuthCaptchaSupport.isCaptchaTriggerReached(-1L, 0L));
	}

	@Test
	void belowThresholdSkipsCaptcha() {
		assertFalse(AuthCaptchaSupport.isCaptchaTriggerReached(3L, null));
		assertFalse(AuthCaptchaSupport.isCaptchaTriggerReached(3L, 0L));
		assertFalse(AuthCaptchaSupport.isCaptchaTriggerReached(3L, 2L));
	}

	@Test
	void reachingThresholdRequiresCaptcha() {
		assertTrue(AuthCaptchaSupport.isCaptchaTriggerReached(3L, 3L));
		assertTrue(AuthCaptchaSupport.isCaptchaTriggerReached(3L, 5L));
	}

}
