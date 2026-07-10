package com.aiwork.admin.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 飞书 JIT 建号所需的通讯录用户信息
 *
 * @author ai-work
 * @date 2026-07-10
 */
@Data
public class FeishuUserInfo implements Serializable {

	private static final long serialVersionUID = 1L;

	/**
	 * 应用内用户唯一标识
	 */
	private String openId;

	/**
	 * 姓名
	 */
	private String name;

	/**
	 * 手机号(飞书原始值,可能带 +86 前缀)
	 */
	private String mobile;

	/**
	 * 头像 URL
	 */
	private String avatar;

	/**
	 * 企业内用户 ID(飞书 user_id)
	 */
	private String tenantUserId;

	/**
	 * 用户直属部门的 open_department_id 列表
	 */
	private List<String> deptOpenIds;

	/**
	 * 直属部门及其全部祖先(父在前),取失败时为空列表(降级)
	 */
	private List<FeishuDeptInfo> deptChain;

}
