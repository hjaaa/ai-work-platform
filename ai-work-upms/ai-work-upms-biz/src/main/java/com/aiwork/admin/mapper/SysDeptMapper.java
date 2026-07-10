/*
 *
 *      Copyright (c) 2018-2026, lengleng All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice,
 *  this list of conditions and the following disclaimer.
 *  Redistributions in binary form must reproduce the above copyright
 *  notice, this list of conditions and the following disclaimer in the
 *  documentation and/or other materials provided with the distribution.
 *  Neither the name of the pig4cloud.com developer nor the names of its
 *  contributors may be used to endorse or promote products derived from
 *  this software without specific prior written permission.
 *  Author: lengleng (wangiegie@gmail.com)
 *
 */

package com.aiwork.admin.mapper;

import com.aiwork.admin.api.entity.SysDept;
import com.github.yulichang.base.MPJBaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 部门管理 Mapper 接口
 *
 * @author lengleng
 * @date 2025/06/27
 */
@Mapper
public interface SysDeptMapper extends MPJBaseMapper<SysDept> {

	/**
	 * 根据用户ID查询部门列表
	 * @param userId 用户ID
	 * @return 部门列表
	 */
	List<SysDept> listDeptsByUserId(@Param("userId") Long userId);

	/**
	 * 根据飞书部门 ID 查询部门映射,包含逻辑删除记录
	 * @param feishuDeptId 飞书 open_department_id
	 * @return 部门映射
	 */
	@Select("SELECT * FROM sys_dept WHERE feishu_dept_id = #{feishuDeptId} LIMIT 1")
	SysDept selectIncludingDeletedByFeishuDeptId(@Param("feishuDeptId") String feishuDeptId);

}
