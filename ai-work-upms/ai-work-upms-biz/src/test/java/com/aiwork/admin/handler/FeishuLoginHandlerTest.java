package com.aiwork.admin.handler;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONUtil;
import com.aiwork.admin.api.dto.UserDTO;
import com.aiwork.admin.api.entity.SysSocialDetails;
import com.aiwork.admin.api.entity.SysUser;
import com.aiwork.admin.api.entity.SysUserSocial;
import com.aiwork.admin.mapper.SysSocialDetailsMapper;
import com.aiwork.admin.mapper.SysUserSocialMapper;
import com.aiwork.admin.service.SysUserService;
import com.aiwork.common.core.exception.CheckedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
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

	private SysSocialDetails feishuSocialDetails() {
		SysSocialDetails socialDetails = new SysSocialDetails();
		socialDetails.setAppId("cli_test_app");
		socialDetails.setAppSecret("secret_test_value");
		socialDetails.setRedirectUrl("https://example.com/callback");
		return socialDetails;
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
	void identifyReturnsNullWhenTokenCodeIsNotZero() {
		when(sysSocialDetailsMapper.selectOne(any())).thenReturn(feishuSocialDetails());

		try (MockedStatic<HttpUtil> mockedHttpUtil = mockStatic(HttpUtil.class);
				MockedStatic<HttpRequest> mockedHttpRequest = mockStatic(HttpRequest.class)) {
			mockedHttpUtil.when(
					() -> HttpUtil.post(eq("https://open.feishu.cn/open-apis/authen/v2/oauth/token"), anyString()))
				.thenReturn("""
					{
						"code": 99991663,
						"msg": "invalid authorization code",
						"access_token": "u-test-token"
					}
					""");

			assertNull(newHandler().identify("test-code"));
			mockedHttpRequest.verifyNoInteractions();
		}
	}

	@Test
	void identifyReturnsNullWhenUserInfoCodeIsNotZero() {
		when(sysSocialDetailsMapper.selectOne(any())).thenReturn(feishuSocialDetails());
		HttpRequest httpRequest = mock(HttpRequest.class);
		HttpResponse httpResponse = mock(HttpResponse.class);
		when(httpRequest.header("Authorization", "Bearer u-test-token")).thenReturn(httpRequest);
		when(httpRequest.execute()).thenReturn(httpResponse);
		when(httpResponse.body()).thenReturn("""
			{
				"code": 20045031,
				"msg": "invalid user access token",
				"data": {
					"open_id": "ou_test_open_id"
				}
			}
			""");

		try (MockedStatic<HttpUtil> mockedHttpUtil = mockStatic(HttpUtil.class);
				MockedStatic<HttpRequest> mockedHttpRequest = mockStatic(HttpRequest.class)) {
			mockedHttpUtil.when(
					() -> HttpUtil.post(eq("https://open.feishu.cn/open-apis/authen/v2/oauth/token"), anyString()))
				.thenReturn("""
					{
						"code": 0,
						"access_token": "u-test-token"
					}
					""");
			mockedHttpRequest.when(() -> HttpRequest.get("https://open.feishu.cn/open-apis/authen/v1/user_info"))
				.thenReturn(httpRequest);

			assertNull(newHandler().identify("test-code"));
		}
	}

	@Test
	void identifyDebugLogDoesNotExposeSensitiveFeishuResponseFields() {
		when(sysSocialDetailsMapper.selectOne(any())).thenReturn(feishuSocialDetails());
		HttpRequest httpRequest = mock(HttpRequest.class);
		HttpResponse httpResponse = mock(HttpResponse.class);
		when(httpRequest.header("Authorization", "Bearer u-test-token")).thenReturn(httpRequest);
		when(httpRequest.execute()).thenReturn(httpResponse);
		String tokenResponse = """
			{
				"code": 0,
				"msg": "success",
				"access_token": "u-test-token"
			}
			""";
		String userInfoResponse = """
			{
				"code": 0,
				"data": {
					"open_id": "ou_test_open_id"
				}
			}
			""";
		when(httpResponse.body()).thenReturn(userInfoResponse);

		Logger logger = (Logger) LoggerFactory.getLogger(FeishuLoginHandler.class);
		Level previousLevel = logger.getLevel();
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		logger.setLevel(Level.DEBUG);

		try (MockedStatic<HttpUtil> mockedHttpUtil = mockStatic(HttpUtil.class);
				MockedStatic<HttpRequest> mockedHttpRequest = mockStatic(HttpRequest.class)) {
			mockedHttpUtil.when(
					() -> HttpUtil.post(eq("https://open.feishu.cn/open-apis/authen/v2/oauth/token"), anyString()))
				.thenReturn(tokenResponse);
			mockedHttpRequest.when(() -> HttpRequest.get("https://open.feishu.cn/open-apis/authen/v1/user_info"))
				.thenReturn(httpRequest);

			assertEquals("ou_test_open_id", newHandler().identify("test-code"));
		}
		finally {
			logger.detachAppender(appender);
			logger.setLevel(previousLevel);
		}

		List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
		String combinedLog = String.join("\n", messages);

		assertAll(
				() -> assertTrue(combinedLog.contains("code=0")),
				() -> assertTrue(combinedLog.contains("accessTokenPresent=true")),
				() -> assertTrue(combinedLog.contains("openIdPresent=true")),
				() -> assertFalse(combinedLog.contains(tokenResponse.strip())),
				() -> assertFalse(combinedLog.contains(userInfoResponse.strip())),
				() -> assertFalse(combinedLog.contains("u-test-token")),
				() -> assertFalse(combinedLog.contains("ou_test_open_id")),
				() -> assertFalse(combinedLog.contains("access_token")),
				() -> assertFalse(combinedLog.contains("open_id")));
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

		assertAll(
				() -> assertTrue(summary.contains("code=0")),
				() -> assertTrue(summary.contains("accessTokenPresent=true")),
				() -> assertFalse(summary.contains("u-test-token")),
				() -> assertFalse(summary.contains("ou_test_open_id")),
				() -> assertFalse(summary.contains("access_token")),
				() -> assertFalse(summary.contains("open_id")));
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

		assertAll(
				() -> assertTrue(summary.contains("code=0")),
				() -> assertTrue(summary.contains("openIdPresent=true")),
				() -> assertFalse(summary.contains("ou_test_open_id")),
				() -> assertFalse(summary.contains("open_id")));
	}

}
