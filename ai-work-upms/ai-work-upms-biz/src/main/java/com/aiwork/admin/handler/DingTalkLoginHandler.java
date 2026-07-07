/*
 *    Copyright (c) 2018-2026, lengleng All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 * Neither the name of the pig4cloud.com developer nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 * Author: lengleng (wangiegie@gmail.com)
 */

package com.aiwork.admin.handler;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpUtil;
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
 * 钉钉登录
 *
 * @author lengleng
 * @date 2023-10-14
 */
@Slf4j
@Component("DINGTALK")
public class DingTalkLoginHandler extends AbstractUserSocialHandler {

	private final SysSocialDetailsMapper sysSocialDetailsMapper;

	public DingTalkLoginHandler(SysUserService sysUserService, SysUserSocialMapper sysUserSocialMapper,
			SysSocialDetailsMapper sysSocialDetailsMapper) {
		super(sysUserService, sysUserSocialMapper);
		this.sysSocialDetailsMapper = sysSocialDetailsMapper;
	}

	@Override
	protected LoginTypeEnum loginType() {
		return LoginTypeEnum.DINGTALK;
	}

	@Override
	public String identify(String code) {
		SysSocialDetails condition = new SysSocialDetails();
		condition.setType(LoginTypeEnum.DINGTALK.getType());
		SysSocialDetails socialDetails = sysSocialDetailsMapper.selectOne(new QueryWrapper<>(condition));
		if (socialDetails == null) {
			log.warn("dingtalk social details not configured, type: {}", LoginTypeEnum.DINGTALK.getType());
			return null;
		}

		String accessTokenResult = HttpUtil.post("https://api.dingtalk.com/v1.0/oauth2/userAccessToken",
				JSONUtil.createObj()
					.set("clientId", socialDetails.getAppId())
					.set("clientSecret", socialDetails.getAppSecret())
					.set("grantType", "authorization_code")
					.set("code", code)
					.toString());
		log.debug("获取钉钉Token响应报文：{}", accessTokenResult);

		String accessToken = JSONUtil.parseObj(accessTokenResult).getStr("accessToken");
		String userResult = HttpRequest.get("https://api.dingtalk.com/v1.0/contact/users/me")
			.header("x-acs-dingtalk-access-token", accessToken)
			.execute()
			.body();
		log.debug("获取钉钉UserId响应报文:{}", userResult);

		return JSONUtil.parseObj(userResult).getStr("openId");
	}

	@Override
	protected CheckedException bindFailed() {
		return new CheckedException(MsgUtils.getMessage(UpmsErrorCodes.SYS_DINGTALK_BIND_FAILED));
	}

}
