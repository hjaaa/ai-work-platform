package com.aiwork.admin.handler;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.aiwork.admin.api.constant.UpmsErrorCodes;
import com.aiwork.admin.api.dto.UserDTO;
import com.aiwork.admin.api.dto.UserInfo;
import com.aiwork.admin.api.entity.SysUser;
import com.aiwork.admin.api.entity.SysUserSocial;
import com.aiwork.admin.mapper.SysUserSocialMapper;
import com.aiwork.admin.service.SysUserService;
import com.aiwork.common.core.constant.enums.LoginTypeEnum;
import com.aiwork.common.core.exception.CheckedException;
import com.aiwork.common.core.util.MsgUtils;
import com.aiwork.common.core.util.R;
import lombok.extern.slf4j.Slf4j;

/**
 * 基于 sys_user_social 绑定关系表的社交登录基类：
 * info/bind 统一走绑定表，子类只需实现 identify 与 loginType/bindFailed
 *
 * @author ai-work
 * @date 2026-07-07
 */
@Slf4j
public abstract class AbstractUserSocialHandler extends AbstractLoginHandler {

	protected final SysUserService sysUserService;

	protected final SysUserSocialMapper sysUserSocialMapper;

	protected AbstractUserSocialHandler(SysUserService sysUserService, SysUserSocialMapper sysUserSocialMapper) {
		this.sysUserService = sysUserService;
		this.sysUserSocialMapper = sysUserSocialMapper;
	}

	/**
	 * 当前处理器的社交登录类型
	 * @return 登录类型枚举
	 */
	protected abstract LoginTypeEnum loginType();

	/**
	 * 构造绑定失败异常，子类提供各自的错误文案，测试中可覆写
	 * @return 绑定失败异常
	 */
	protected abstract CheckedException bindFailed();

	/**
	 * 构造「三方账号已被他人绑定」异常，测试中可覆写
	 * @return 已绑定冲突异常
	 */
	protected CheckedException socialAlreadyBound() {
		return new CheckedException(MsgUtils.getMessage(UpmsErrorCodes.SYS_SOCIAL_ALREADY_BOUND));
	}

	@Override
	public UserInfo info(String identify) {
		if (StrUtil.isBlank(identify)) {
			log.warn("{} identify is blank, skip user info query", loginType().getType());
			return null;
		}

		SysUserSocial binding = selectByIdentify(identify);
		if (binding == null) {
			log.info("{} social binding not found, identify: {}", loginType().getType(), identify);
			return null;
		}

		UserDTO userDTO = new UserDTO();
		userDTO.setUserId(binding.getUserId());
		R<UserInfo> userInfoR = sysUserService.getUserInfo(userDTO);
		return userInfoR.getData();
	}

	@Override
	public Boolean bind(SysUser user, String identify) {
		if (StrUtil.isBlank(identify)) {
			log.warn("{} identify is blank, refuse binding, userId: {}", loginType().getType(), user.getUserId());
			throw bindFailed();
		}

		SysUserSocial existed = selectByIdentify(identify);
		if (existed != null && !existed.getUserId().equals(user.getUserId())) {
			log.warn("{} identify already bound to another user, identify: {}", loginType().getType(), identify);
			throw socialAlreadyBound();
		}

		SysUserSocial condition = new SysUserSocial();
		condition.setType(loginType().getType());
		condition.setUserId(user.getUserId());
		SysUserSocial mine = sysUserSocialMapper.selectOne(new QueryWrapper<>(condition));
		if (mine == null) {
			SysUserSocial social = new SysUserSocial();
			social.setUserId(user.getUserId());
			social.setType(loginType().getType());
			social.setIdentify(identify);
			sysUserSocialMapper.insert(social);
		}
		else {
			mine.setIdentify(identify);
			sysUserSocialMapper.updateById(mine);
		}
		return Boolean.TRUE;
	}

	private SysUserSocial selectByIdentify(String identify) {
		SysUserSocial condition = new SysUserSocial();
		condition.setType(loginType().getType());
		condition.setIdentify(identify);
		return sysUserSocialMapper.selectOne(new QueryWrapper<>(condition));
	}

}
