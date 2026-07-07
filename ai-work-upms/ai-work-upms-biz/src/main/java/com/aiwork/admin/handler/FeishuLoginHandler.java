package com.aiwork.admin.handler;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.aiwork.admin.api.constant.UpmsErrorCodes;
import com.aiwork.admin.api.entity.SysSocialDetails;
import com.aiwork.admin.mapper.SysSocialDetailsMapper;
import com.aiwork.admin.mapper.SysUserSocialMapper;
import com.aiwork.admin.service.SysUserService;
import com.aiwork.common.core.constant.enums.LoginTypeEnum;
import com.aiwork.common.core.exception.CheckedException;
import com.aiwork.common.core.util.MsgUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 飞书登录：网页扫码授权码换 user_access_token 后取 open_id，
 * 用户映射走 sys_user_social 绑定关系表
 *
 * @author ai-work
 * @date 2026-07-07
 */
@Slf4j
@Component("FEISHU")
public class FeishuLoginHandler extends AbstractUserSocialHandler {

	/**
	 * 授权码换 user_access_token（v2，JSON 体，access_token 在响应顶层）
	 */
	private static final String TOKEN_URL = "https://open.feishu.cn/open-apis/authen/v2/oauth/token";

	/**
	 * 获取登录用户信息（Bearer user_access_token，open_id 在 data.open_id）
	 */
	private static final String USER_INFO_URL = "https://open.feishu.cn/open-apis/authen/v1/user_info";

	private final SysSocialDetailsMapper sysSocialDetailsMapper;

	public FeishuLoginHandler(SysUserService sysUserService, SysUserSocialMapper sysUserSocialMapper,
			SysSocialDetailsMapper sysSocialDetailsMapper) {
		super(sysUserService, sysUserSocialMapper);
		this.sysSocialDetailsMapper = sysSocialDetailsMapper;
	}

	@Override
	protected LoginTypeEnum loginType() {
		return LoginTypeEnum.FEISHU;
	}

	@Override
	public String identify(String code) {
		SysSocialDetails condition = new SysSocialDetails();
		condition.setType(LoginTypeEnum.FEISHU.getType());
		SysSocialDetails socialDetails = sysSocialDetailsMapper.selectOne(new QueryWrapper<>(condition));
		if (socialDetails == null) {
			log.warn("feishu social details not configured, type: {}", LoginTypeEnum.FEISHU.getType());
			return null;
		}

		String tokenResult = HttpUtil.post(TOKEN_URL,
				JSONUtil.createObj()
					.set("grant_type", "authorization_code")
					.set("client_id", socialDetails.getAppId())
					.set("client_secret", socialDetails.getAppSecret())
					.set("code", code)
					.set("redirect_uri", socialDetails.getRedirectUrl())
					.toString());
		JSONObject tokenObj = JSONUtil.parseObj(tokenResult);
		if (log.isDebugEnabled()) {
			log.debug("获取飞书Token响应摘要：{}", buildTokenLogSummary(tokenObj));
		}
		Integer tokenCode = tokenObj.getInt("code");
		if (!Integer.valueOf(0).equals(tokenCode)) {
			log.warn("feishu token response code invalid, code: {}", tokenCode);
			return null;
		}
		String accessToken = tokenObj.getStr("access_token");
		if (StrUtil.isBlank(accessToken)) {
			log.warn("feishu access token missing, code: {}", tokenCode);
			return null;
		}

		String userResult = HttpRequest.get(USER_INFO_URL)
			.header("Authorization", "Bearer " + accessToken)
			.execute()
			.body();
		JSONObject userObj = JSONUtil.parseObj(userResult);
		if (log.isDebugEnabled()) {
			log.debug("获取飞书用户信息响应摘要:{}", buildUserInfoLogSummary(userObj));
		}
		Integer userCode = userObj.getInt("code");
		if (!Integer.valueOf(0).equals(userCode)) {
			log.warn("feishu user info response code invalid, code: {}", userCode);
			return null;
		}

		return userObj.getByPath("data.open_id", String.class);
	}

	static String buildTokenLogSummary(JSONObject tokenObj) {
		return "code=" + tokenObj.getStr("code") + ", accessTokenPresent="
				+ StrUtil.isNotBlank(tokenObj.getStr("access_token"));
	}

	static String buildUserInfoLogSummary(JSONObject userObj) {
		return "code=" + userObj.getStr("code") + ", openIdPresent="
				+ StrUtil.isNotBlank(userObj.getByPath("data.open_id", String.class));
	}

	@Override
	protected CheckedException bindFailed() {
		return new CheckedException(MsgUtils.getMessage(UpmsErrorCodes.SYS_FEISHU_BIND_FAILED));
	}

}
