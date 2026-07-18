/*
 *
 *      Copyright (c) 2018-2025, lengleng All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
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
 *
 */

package com.aiwork.baas.mapper;

import com.aiwork.baas.entity.BaasProject;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * BaaS 项目 Mapper。
 *
 * @author ai-work
 * @date 2026/07/17
 */
@Mapper
public interface BaasProjectMapper extends BaseMapper<BaasProject> {

    /**
     * 锁定项目行(须在事务内调用),epoch 守卫事务的入口(spec §9.2)。
     * @param projectId 项目 ID
     * @return 项目行,不存在返回 null
     */
    @Select("SELECT * FROM baas_project WHERE id = #{projectId} FOR UPDATE")
    BaasProject selectByIdForUpdate(@Param("projectId") Long projectId);

    /**
     * 条件递增项目 fencing epoch,配合 selectByIdForUpdate 在所有权事务内调用。
     * @param projectId 项目 ID
     * @param expectedOld 递增前观察到的 epoch
     * @return 影响行数,0 表示并发修改
     */
    @Update("UPDATE baas_project SET ddl_fence_epoch = #{expectedOld} + 1 "
            + "WHERE id = #{projectId} AND ddl_fence_epoch = #{expectedOld}")
    int bumpFenceEpoch(@Param("projectId") Long projectId, @Param("expectedOld") long expectedOld);

}
