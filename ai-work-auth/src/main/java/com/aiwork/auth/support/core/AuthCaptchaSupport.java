package com.aiwork.auth.support.core;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.aiwork.admin.api.entity.SysOauthClientDetails;
import com.aiwork.common.core.constant.CacheConstants;
import com.aiwork.common.core.constant.CommonConstants;
import com.aiwork.common.core.constant.enums.CaptchaFlagTypeEnum;
import com.aiwork.common.core.exception.ValidateCodeException;
import com.aiwork.common.core.util.MsgUtils;
import com.aiwork.common.data.cache.RedisUtils;
import com.aiwork.common.data.resolver.ParamResolver;
import com.aiwork.common.security.captcha.CaptchaResult;
import com.aiwork.common.security.captcha.CaptchaValidator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 授权登录验证码支持组件
 *
 * <p>
 * 统一处理验证码开关判断、授权客户端解析和验证码校验，避免在多个过滤器和控制器中重复实现。
 * </p>
 *
 * @author ai-work
 * @date 2026-03-20
 */
@Component
@RequiredArgsConstructor
public class AuthCaptchaSupport {

	private final RequestCache requestCache = new HttpSessionRequestCache();

	private final CaptchaValidator captchaValidator;

	private final OauthClientDetailsLoader clientDetailsLoader;

	/**
	 * 解析当前授权流程中的真实客户端ID
	 * @param request 当前请求
	 * @param response 当前响应
	 * @param includeCurrentRequestId 是否优先读取当前请求参数中的 client_id
	 * @return 授权客户端ID
	 */
	public String resolveAuthorizationClientId(HttpServletRequest request, HttpServletResponse response,
			boolean includeCurrentRequestId) {
		return resolveAuthorizationParameter(request, response, OAuth2ParameterNames.CLIENT_ID,
				includeCurrentRequestId);
	}

	/**
	 * 判断客户端是否开启验证码
	 * @param clientId 客户端ID
	 * @return true 开启验证码
	 */
	public boolean isCaptchaEnabled(String clientId) {
		if (StrUtil.isBlank(clientId)) {
			return false;
		}

		SysOauthClientDetails clientDetails = clientDetailsLoader.getByClientId(clientId);
		if (clientDetails == null || StrUtil.isBlank(clientDetails.getAdditionalInformation())) {
			return true;
		}

		JSONObject information = JSONUtil.parseObj(clientDetails.getAdditionalInformation());
		return !StrUtil.equals(CaptchaFlagTypeEnum.OFF.getType(), information.getStr(CommonConstants.CAPTCHA_FLAG));
	}

	/**
	 * 验证码触发失败次数系统参数键
	 */
	private static final String CAPTCHA_ERROR_TIMES_PARAM = "CAPTCHA_ERROR_TIMES";

	/**
	 * 验证码触发失败次数默认值
	 */
	private static final Long DEFAULT_CAPTCHA_ERROR_TIMES = 3L;

	/**
	 * 判断账号登录失败次数是否已达验证码触发阈值
	 * <p>
	 * 系统参数 CAPTCHA_ERROR_TIMES {@literal <=} 0 表示关闭自适应验证码，每次登录都需校验；
	 * 无法识别账号时按需要验证码处理。
	 * </p>
	 * @param username 登录账号
	 * @return true 本次登录需要校验验证码
	 */
	public boolean isFailureTimesReached(String username) {
		Long threshold = ParamResolver.getLong(CAPTCHA_ERROR_TIMES_PARAM, DEFAULT_CAPTCHA_ERROR_TIMES);
		if (StrUtil.isBlank(username)) {
			return true;
		}
		String key = String.format("%s%s:%s", CacheConstants.GLOBALLY, CacheConstants.LOGIN_ERROR_TIMES, username);
		Long failureTimes = Convert.toLong(RedisUtils.get(key));
		return isCaptchaTriggerReached(threshold, failureTimes);
	}

	/**
	 * 判断失败次数是否达到验证码触发阈值（阈值为空或 {@literal <=} 0 表示每次都校验）
	 * @param threshold 触发阈值
	 * @param failureTimes 当前失败次数
	 * @return true 需要校验验证码
	 */
	static boolean isCaptchaTriggerReached(Long threshold, Long failureTimes) {
		if (threshold == null || threshold <= 0) {
			return true;
		}
		return failureTimes != null && failureTimes >= threshold;
	}

	/**
	 * 校验请求中的验证码
	 * @param request 当前请求
	 * @throws ValidateCodeException 验证码校验失败
	 */
	public void validateCode(HttpServletRequest request) throws ValidateCodeException {
		String code = request.getParameter("code");
		if (StrUtil.isBlank(code)) {
			throw new ValidateCodeException(MsgUtils.getMessage(AuthErrorCodes.AUTH_CAPTCHA_EMPTY));
		}

		String randomStr = request.getParameter("randomStr");
		// https://gitee.com/log4j/pig/issues/IWA0D 手机号场景以 mobile 作为缓存键
		String mobile = request.getParameter("mobile");
		if (StrUtil.isNotBlank(mobile)) {
			randomStr = mobile;
		}

		boolean isBehavior = StrUtil.equalsAnyIgnoreCase(randomStr, CommonConstants.IMAGE_CODE_BLOCK_PUZZLE,
				CommonConstants.IMAGE_CODE_CLICK_WORD);
		String captchaType = isBehavior ? randomStr : CommonConstants.IMAGE_CODE_MATH;
		String captchaVerification = isBehavior ? code
				: randomStr + CommonConstants.CAPTCHA_VERIFICATION_SEPARATOR + code;

		CaptchaResult result = captchaValidator.validate(captchaType, captchaVerification);
		if (!result.isOk()) {
			String errorCode = isBehavior ? AuthErrorCodes.AUTH_CAPTCHA_EMPTY : AuthErrorCodes.AUTH_CAPTCHA_INVALID;
			throw new ValidateCodeException(MsgUtils.getMessage(errorCode));
		}
	}

	private String resolveAuthorizationParameter(HttpServletRequest request, HttpServletResponse response,
			String parameterName, boolean includeCurrentRequestId) {
		if (includeCurrentRequestId) {
			String parameterValue = request.getParameter(parameterName);
			if (StrUtil.isNotBlank(parameterValue)) {
				return parameterValue;
			}
		}

		SavedRequest savedRequest = requestCache.getRequest(request, response);
		if (Objects.isNull(savedRequest)) {
			return null;
		}

		String[] parameterValues = savedRequest.getParameterValues(parameterName);
		if (Objects.isNull(parameterValues) || parameterValues.length == 0) {
			return null;
		}

		return parameterValues[0];
	}

}
