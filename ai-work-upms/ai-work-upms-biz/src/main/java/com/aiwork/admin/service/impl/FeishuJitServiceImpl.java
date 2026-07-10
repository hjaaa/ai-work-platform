package com.aiwork.admin.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.DesensitizedUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aiwork.admin.api.dto.FeishuDeptInfo;
import com.aiwork.admin.api.dto.FeishuUserInfo;
import com.aiwork.admin.api.dto.UserDTO;
import com.aiwork.admin.api.entity.SysDept;
import com.aiwork.admin.api.entity.SysSocialDetails;
import com.aiwork.admin.api.entity.SysUser;
import com.aiwork.admin.api.entity.SysUserSocial;
import com.aiwork.admin.mapper.SysDeptMapper;
import com.aiwork.admin.mapper.SysSocialDetailsMapper;
import com.aiwork.admin.mapper.SysUserSocialMapper;
import com.aiwork.admin.service.FeishuJitService;
import com.aiwork.admin.service.SysUserService;
import com.aiwork.common.core.constant.enums.LoginTypeEnum;
import com.aiwork.common.core.exception.CheckedException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

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

	private static final int HTTP_TIMEOUT_MILLIS = 5000;

	private static final int RANDOM_PASSWORD_LENGTH = 32;

	private static final String PASSWORD_CHARACTERS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	/**
	 * 飞书根部门 ID
	 */
	private static final String ROOT_DEPT_ID = "0";

	private static final String DELETED_FLAG = "1";

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
				.timeout(HTTP_TIMEOUT_MILLIS)
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
			log.warn("feishu contact user request failed", e);
			return null;
		}
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean provision(FeishuUserInfo feishuUser) {
		// 并发扫码下绑定可能已被先到的请求建立
		SysUserSocial condition = new SysUserSocial();
		condition.setType(LoginTypeEnum.FEISHU.getType());
		condition.setIdentify(feishuUser.getOpenId());
		if (sysUserSocialMapper.selectOne(new QueryWrapper<>(condition)) != null) {
			return Boolean.TRUE;
		}

		String phone = normalizeMobile(feishuUser.getMobile());
		List<SysUser> phoneMatches = sysUserService.list(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getPhone, phone));
		if (phoneMatches.size() > 1) {
			// 手机号命中多个用户时无法判定归属,拒绝自动绑定,交人工处理
			log.warn("feishu jit phone matches multiple users, phone: {}", DesensitizedUtil.mobilePhone(phone));
			throw new CheckedException("feishu jit phone matches multiple users");
		}
		SysUser sysUser = phoneMatches.isEmpty() ? createUser(feishuUser, phone) : phoneMatches.get(0);

		SysUserSocial userCondition = new SysUserSocial();
		userCondition.setUserId(sysUser.getUserId());
		userCondition.setType(LoginTypeEnum.FEISHU.getType());
		SysUserSocial existingBinding = sysUserSocialMapper.selectOne(new QueryWrapper<>(userCondition));
		if (existingBinding != null) {
			existingBinding.setIdentify(feishuUser.getOpenId());
			existingBinding.setTenantUserId(feishuUser.getTenantUserId());
			sysUserSocialMapper.updateById(existingBinding);
			return Boolean.TRUE;
		}

		SysUserSocial social = new SysUserSocial();
		social.setUserId(sysUser.getUserId());
		social.setType(LoginTypeEnum.FEISHU.getType());
		social.setIdentify(feishuUser.getOpenId());
		social.setTenantUserId(feishuUser.getTenantUserId());
		sysUserSocialMapper.insert(social);
		return Boolean.TRUE;
	}

	private SysUser createUser(FeishuUserInfo feishuUser, String phone) {
		if (sysUserService.exists(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, phone))) {
			log.warn("feishu jit username already exists, phone: {}", DesensitizedUtil.mobilePhone(phone));
			throw new CheckedException("feishu jit username already exists");
		}
		UserDTO userDTO = new UserDTO();
		userDTO.setUsername(phone);
		// 随机密码仅为满足字段必填,JIT 用户走扫码登录,需要密码时走找回密码
		userDTO.setPassword(generateRandomPassword());
		userDTO.setPhone(phone);
		userDTO.setName(feishuUser.getName());
		userDTO.setNickname(feishuUser.getName());
		userDTO.setAvatar(feishuUser.getAvatar());
		List<Long> deptIds = syncDeptChain(feishuUser);
		if (CollUtil.isNotEmpty(deptIds)) {
			userDTO.setDeptIds(deptIds);
		}
		sysUserService.saveUser(userDTO);

		SysUser created = sysUserService
			.getOne(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, phone), false);
		if (created == null) {
			throw new CheckedException("feishu jit create user failed");
		}
		return created;
	}

	/**
	 * 按父在前的部门链只创建本地缺失节点,返回用户直属部门的本地 ID
	 */
	private List<Long> syncDeptChain(FeishuUserInfo feishuUser) {
		List<FeishuDeptInfo> chain = feishuUser.getDeptChain();
		if (CollUtil.isEmpty(chain)) {
			return Collections.emptyList();
		}
		Map<String, SysDept> existingDepts = new HashMap<>();
		for (FeishuDeptInfo dept : chain) {
			SysDept existing = sysDeptMapper.selectIncludingDeletedByFeishuDeptId(dept.getOpenDeptId());
			if (existing != null) {
				if (DELETED_FLAG.equals(existing.getDelFlag())) {
					log.warn("feishu jit department mapping deleted, degrade to no dept");
					return Collections.emptyList();
				}
				existingDepts.put(dept.getOpenDeptId(), existing);
			}
		}

		Map<String, Long> localIds = new HashMap<>();
		for (FeishuDeptInfo dept : chain) {
			SysDept existing = existingDepts.get(dept.getOpenDeptId());
			if (existing != null) {
				localIds.put(dept.getOpenDeptId(), existing.getDeptId());
				continue;
			}
			SysDept created = new SysDept();
			created.setName(dept.getName());
			Long parentLocalId = ROOT_DEPT_ID.equals(dept.getParentOpenDeptId()) ? 0L
					: localIds.get(dept.getParentOpenDeptId());
			// 父链缺失(超深度截断等)时挂到根
			created.setParentId(parentLocalId == null ? 0L : parentLocalId);
			created.setSortOrder(0);
			created.setFeishuDeptId(dept.getOpenDeptId());
			sysDeptMapper.insert(created);
			localIds.put(dept.getOpenDeptId(), created.getDeptId());
		}
		return feishuUser.getDeptOpenIds()
			.stream()
			.map(localIds::get)
			.filter(Objects::nonNull)
			.collect(Collectors.toList());
	}

	private String normalizeMobile(String mobile) {
		return StrUtil.removePrefix(StrUtil.trimToEmpty(mobile), "+86");
	}

	static String generateRandomPassword() {
		StringBuilder password = new StringBuilder(RANDOM_PASSWORD_LENGTH);
		for (int index = 0; index < RANDOM_PASSWORD_LENGTH; index++) {
			password.append(PASSWORD_CHARACTERS.charAt(SECURE_RANDOM.nextInt(PASSWORD_CHARACTERS.length())));
		}
		return password.toString();
	}

	private String fetchTenantToken(SysSocialDetails socialDetails) {
		try {
			String tokenResult = HttpRequest.post(TENANT_TOKEN_URL)
				.header("Content-Type", "application/json")
				.body(JSONUtil.createObj()
					.set("app_id", socialDetails.getAppId())
					.set("app_secret", socialDetails.getAppSecret())
					.toString())
				.timeout(HTTP_TIMEOUT_MILLIS)
				.execute()
				.body();
			JSONObject tokenObj = JSONUtil.parseObj(tokenResult);
			if (!Integer.valueOf(0).equals(tokenObj.getInt("code"))) {
				log.warn("feishu tenant token response code invalid, code: {}", tokenObj.getInt("code"));
				return null;
			}
			return tokenObj.getStr("tenant_access_token");
		}
		catch (Exception e) {
			log.warn("feishu tenant token request failed", e);
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
			.timeout(HTTP_TIMEOUT_MILLIS)
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
