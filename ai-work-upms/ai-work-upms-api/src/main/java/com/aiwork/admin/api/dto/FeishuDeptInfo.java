package com.aiwork.admin.api.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 飞书部门信息(JIT 部门同步用)
 *
 * @author ai-work
 * @date 2026-07-10
 */
@Data
public class FeishuDeptInfo implements Serializable {

	private static final long serialVersionUID = 1L;

	/**
	 * 飞书 open_department_id
	 */
	private String openDeptId;

	/**
	 * 部门名称
	 */
	private String name;

	/**
	 * 父部门 open_department_id,根为 "0"
	 */
	private String parentOpenDeptId;

}
