package com.aiwork.admin.api.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 用户社交绑定关系表：物理删除（无 del_flag），唯一索引保证
 * 「一个三方账号只绑一个用户、一个用户每类型仅一条」
 *
 * @author ai-work
 * @date 2026-07-07
 */
@Data
@Schema(description = "用户社交绑定关系")
@EqualsAndHashCode(callSuper = true)
public class SysUserSocial extends Model<SysUserSocial> {

	private static final long serialVersionUID = 1L;

	/**
	 * 主键
	 */
	@TableId(type = IdType.ASSIGN_ID)
	@Schema(description = "主键")
	private Long id;

	/**
	 * 平台用户ID
	 */
	@Schema(description = "平台用户ID")
	private Long userId;

	/**
	 * 社交类型（DINGTALK/FEISHU）
	 */
	@Schema(description = "社交类型")
	private String type;

	/**
	 * 第三方用户标识（openId）
	 */
	@Schema(description = "第三方用户标识")
	private String identify;

	/**
	 * 创建人
	 */
	@TableField(fill = FieldFill.INSERT)
	private String createBy;

	/**
	 * 修改人
	 */
	@TableField(fill = FieldFill.UPDATE)
	private String updateBy;

	/**
	 * 创建时间
	 */
	@TableField(fill = FieldFill.INSERT)
	private LocalDateTime createTime;

	/**
	 * 更新时间
	 */
	@TableField(fill = FieldFill.UPDATE)
	private LocalDateTime updateTime;

}
