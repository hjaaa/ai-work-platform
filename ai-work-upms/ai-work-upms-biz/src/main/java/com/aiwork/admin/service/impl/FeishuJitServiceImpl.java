package com.aiwork.admin.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.aiwork.admin.api.dto.FeishuDeptInfo;
import com.aiwork.admin.api.dto.FeishuUserInfo;
import com.aiwork.admin.api.entity.SysSocialDetails;
import com.aiwork.admin.mapper.SysDeptMapper;
import com.aiwork.admin.mapper.SysSocialDetailsMapper;
import com.aiwork.admin.mapper.SysUserSocialMapper;
import com.aiwork.admin.service.FeishuJitService;
import com.aiwork.admin.service.SysUserService;
import com.aiwork.common.core.constant.enums.LoginTypeEnum;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * 飞书 JIT 自动建号:通讯录调用走应用身份(tenant_access_token)
 *
 * @author ai-work
 * @date 2026-07-10
 */
@Slf4j
@Service
@AllArgsConstructor
public class FeishuJitServiceImpl implements FeishuJitService {

	private static final String TENANT_TOKEN_URL = "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal";

	private static final String CONTACT_USER_URL = "https://open.feishu.cn/open-apis/contact/v3/users/";

	private static final String CONTACT_DEPT_URL = "https://open.feishu.cn/open-apis/contact/v3/departments/";

	/**
	 * 飞书根部门 ID
	 */
	private static final String ROOT_DEPT_ID = "0";

	/**
	 * 部门父链最大深度,防脏数据成环
	 */
	private static final int MAX_DEPT_DEPTH = 20;

	private final SysSocialDetailsMapper sysSocialDetailsMapper;

	private final SysUserService sysUserService;

	private final SysDeptMapper sysDeptMapper;

	private final SysUserSocialMapper sysUserSocialMapper;

	@Override
	public FeishuUserInfo fetchUser(String openId) {
		SysSocialDetails condition = new SysSocialDetails();
		condition.setType(LoginTypeEnum.FEISHU.getType());
		SysSocialDetails socialDetails = sysSocialDetailsMapper.selectOne(new QueryWrapper<>(condition));
		if (socialDetails == null) {
			log.warn("feishu jit skipped, social details not configured");
			return null;
		}

		String tenantToken = fetchTenantToken(socialDetails);
		if (StrUtil.isBlank(tenantToken)) {
			return null;
		}
		return fetchContactUser(openId, tenantToken);
	}

	private FeishuUserInfo fetchContactUser(String openId, String tenantToken) {
		try {
			String userResult = HttpRequest
				.get(CONTACT_USER_URL + openId + "?user_id_type=open_id&department_id_type=open_department_id")
				.header("Authorization", "Bearer " + tenantToken)
				.execute()
				.body();
			JSONObject userObj = JSONUtil.parseObj(userResult);
			if (!Integer.valueOf(0).equals(userObj.getInt("code"))) {
				log.warn("feishu contact user response code invalid, code: {}", userObj.getInt("code"));
				return null;
			}

			JSONObject user = userObj.getByPath("data.user", JSONObject.class);
			if (user == null) {
				log.warn("feishu contact user data missing");
				return null;
			}
			FeishuUserInfo info = new FeishuUserInfo();
			info.setOpenId(openId);
			info.setName(user.getStr("name"));
			info.setMobile(user.getStr("mobile"));
			info.setAvatar(user.getByPath("avatar.avatar_240", String.class));
			info.setTenantUserId(user.getStr("user_id"));
			List<String> deptOpenIds = user.getJSONArray("department_ids") == null ? Collections.emptyList()
					: user.getJSONArray("department_ids").toList(String.class);
			info.setDeptOpenIds(deptOpenIds);
			info.setDeptChain(fetchDeptChain(deptOpenIds, tenantToken));
			return info;
		}
		catch (Exception e) {
			log.warn("feishu contact user request failed, exception type: {}", e.getClass().getSimpleName());
			return null;
		}
	}

	@Override
	public Boolean provision(FeishuUserInfo feishuUser) {
		throw new UnsupportedOperationException("implemented in next task");
	}

	private String fetchTenantToken(SysSocialDetails socialDetails) {
		try {
			String tokenResult = HttpUtil.post(TENANT_TOKEN_URL,
					JSONUtil.createObj()
						.set("app_id", socialDetails.getAppId())
						.set("app_secret", socialDetails.getAppSecret())
						.toString());
			JSONObject tokenObj = JSONUtil.parseObj(tokenResult);
			if (!Integer.valueOf(0).equals(tokenObj.getInt("code"))) {
				log.warn("feishu tenant token response code invalid, code: {}", tokenObj.getInt("code"));
				return null;
			}
			return tokenObj.getStr("tenant_access_token");
		}
		catch (Exception e) {
			log.warn("feishu tenant token request failed, exception type: {}", e.getClass().getSimpleName());
			return null;
		}
	}

	/**
	 * 拉取用户直属部门及全部祖先,父在前;任何失败整体降级为空列表(无部门建号)
	 */
	private List<FeishuDeptInfo> fetchDeptChain(List<String> deptOpenIds, String tenantToken) {
		try {
			Map<String, FeishuDeptInfo> visited = new LinkedHashMap<>();
			for (String deptOpenId : deptOpenIds) {
				LinkedList<FeishuDeptInfo> branch = new LinkedList<>();
				String current = deptOpenId;
				int depth = 0;
				while (StrUtil.isNotBlank(current) && !ROOT_DEPT_ID.equals(current) && depth++ < MAX_DEPT_DEPTH
						&& !visited.containsKey(current)) {
					FeishuDeptInfo dept = fetchDept(current, tenantToken);
					if (dept == null) {
						log.warn("feishu dept fetch failed, degrade to no dept");
						return Collections.emptyList();
					}
					branch.addFirst(dept);
					current = dept.getParentOpenDeptId();
				}
				branch.forEach(dept -> visited.put(dept.getOpenDeptId(), dept));
			}
			return new ArrayList<>(visited.values());
		}
		catch (Exception e) {
			log.warn("feishu dept chain fetch failed, degrade to no dept", e);
			return Collections.emptyList();
		}
	}

	private FeishuDeptInfo fetchDept(String openDeptId, String tenantToken) {
		String result = HttpRequest.get(CONTACT_DEPT_URL + openDeptId + "?department_id_type=open_department_id")
			.header("Authorization", "Bearer " + tenantToken)
			.execute()
			.body();
		JSONObject obj = JSONUtil.parseObj(result);
		if (!Integer.valueOf(0).equals(obj.getInt("code"))) {
			log.warn("feishu dept response code invalid, code: {}", obj.getInt("code"));
			return null;
		}
		JSONObject dept = obj.getByPath("data.department", JSONObject.class);
		if (dept == null) {
			return null;
		}
		FeishuDeptInfo info = new FeishuDeptInfo();
		info.setOpenDeptId(openDeptId);
		info.setName(dept.getStr("name"));
		info.setParentOpenDeptId(dept.getStr("parent_department_id"));
		return info;
	}

}
