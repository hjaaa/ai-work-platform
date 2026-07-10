package com.aiwork.admin.service;

import com.aiwork.admin.api.dto.FeishuUserInfo;

/**
 * 飞书 JIT 自动建号服务:扫码登录查无绑定时,拉取通讯录信息并自动建号/绑定
 *
 * @author ai-work
 * @date 2026-07-10
 */
public interface FeishuJitService {

	/**
	 * 以应用身份拉取飞书用户信息(含部门链,HTTP 调用,须在事务外执行)
	 * @param openId 飞书 open_id
	 * @return 用户信息;配置缺失或接口失败返回 null
	 */
	FeishuUserInfo fetchUser(String openId);

	/**
	 * 建号或绑定存量用户(纯 DB 操作,事务内执行)
	 * @param feishuUser 飞书用户信息(mobile 必须非空)
	 * @return 是否成功
	 */
	Boolean provision(FeishuUserInfo feishuUser);

}
