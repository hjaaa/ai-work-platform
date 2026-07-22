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

import com.aiwork.baas.entity.BaasJwtKey;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * JWT 签名密钥 Mapper。
 *
 * @author ai-work
 * @date 2026/07/17
 */
@Mapper
public interface BaasJwtKeyMapper extends BaseMapper<BaasJwtKey> {

    /**
     * 锁定项目全部 JWT 密钥行(须在事务内调用),防并发轮换产生双 CURRENT(spec §7.6)。
     * @param projectId 项目 ID
     * @return 该项目全部密钥行
     */
    @Select("SELECT id, project_id, kid, secret_cipher, status, valid_until, create_time, update_time "
            + "FROM baas_jwt_key WHERE project_id = #{projectId} FOR UPDATE")
    @Results(id = "baasJwtKeyResult", value = {
            @Result(column = "id", property = "id", id = true),
            @Result(column = "project_id", property = "projectId"),
            @Result(column = "kid", property = "kid"),
            @Result(column = "secret_cipher", property = "secretCipher"),
            @Result(column = "status", property = "status"),
            @Result(column = "valid_until", property = "validUntil"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime") })
    List<BaasJwtKey> selectByProjectForUpdate(@Param("projectId") Long projectId);

}
