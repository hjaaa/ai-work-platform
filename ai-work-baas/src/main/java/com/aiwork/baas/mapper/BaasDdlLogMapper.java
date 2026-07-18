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

import com.aiwork.baas.entity.BaasDdlLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Schema 操作日志 Mapper。
 *
 * @author ai-work
 * @date 2026/07/17
 */
@Mapper
public interface BaasDdlLogMapper extends BaseMapper<BaasDdlLog> {

    /**
     * 按幂等键查询日志。
     */
    @Select("SELECT * FROM baas_ddl_log WHERE project_id = #{projectId} AND operation_id = #{operationId}")
    BaasDdlLog selectByProjectAndOperation(@Param("projectId") Long projectId,
            @Param("operationId") String operationId);

    /**
     * FAILED 重试分支:并发重试有且仅有一个成功。
     */
    @Update("UPDATE baas_ddl_log SET owner_token = #{newToken}, status = 'RUNNING', "
            + "retry_count = retry_count + 1, fence_epoch = #{newEpoch} "
            + "WHERE id = #{logId} AND owner_token = #{observedToken} AND status = 'FAILED'")
    int casRetryFailed(@Param("logId") Long logId, @Param("observedToken") String observedToken,
            @Param("newToken") String newToken, @Param("newEpoch") long newEpoch);

    /**
     * 陈旧 RUNNING 接管分支。
     */
    @Update("UPDATE baas_ddl_log SET owner_token = #{newToken}, status = 'RUNNING', fence_epoch = #{newEpoch} "
            + "WHERE id = #{logId} AND owner_token = #{observedToken} AND status = 'RUNNING'")
    int casTakeOverRunning(@Param("logId") Long logId, @Param("observedToken") String observedToken,
            @Param("newToken") String newToken, @Param("newEpoch") long newEpoch);

    /**
     * PENDING cleanup 认领分支。
     */
    @Update("UPDATE baas_ddl_log SET owner_token = #{newToken}, status = 'RUNNING', fence_epoch = #{newEpoch} "
            + "WHERE id = #{logId} AND status = 'PENDING' AND owner_token IS NULL")
    int casClaimPending(@Param("logId") Long logId, @Param("newToken") String newToken,
            @Param("newEpoch") long newEpoch);

    /**
     * 检查点推进,RUNNING fencing 条件更新(spec §9.2)。
     */
    @Update("UPDATE baas_ddl_log SET step = #{step} "
            + "WHERE id = #{logId} AND owner_token = #{ownerToken} AND status = 'RUNNING'")
    int advanceStepGuarded(@Param("logId") Long logId, @Param("ownerToken") String ownerToken,
            @Param("step") String step);

    /**
     * 终态写入(SUCCESS/FAILED),owner_token + RUNNING + fence_epoch 三重守卫。
     */
    @Update("UPDATE baas_ddl_log SET status = #{status}, step = #{step}, "
            + "result_snapshot = #{resultSnapshot}, error_msg = #{errorMsg} "
            + "WHERE id = #{logId} AND owner_token = #{ownerToken} AND status = 'RUNNING' "
            + "AND fence_epoch = #{fenceEpoch}")
    int finishGuarded(@Param("logId") Long logId, @Param("ownerToken") String ownerToken,
            @Param("fenceEpoch") long fenceEpoch, @Param("status") String status, @Param("step") String step,
            @Param("resultSnapshot") String resultSnapshot, @Param("errorMsg") String errorMsg);

    /**
     * HTTP 陈旧 RUNNING 兜底：旧 owner_token 与 fence_epoch 双条件 CAS 置 FAILED。
     */
    @Update("UPDATE baas_ddl_log SET status = 'FAILED', fence_epoch = #{newEpoch}, error_msg = #{errorCode} "
            + "WHERE id = #{logId} AND owner_token = #{observedToken} AND fence_epoch = #{observedEpoch} "
            + "AND status = 'RUNNING'")
    int casForceFailRunning(@Param("logId") Long logId, @Param("observedToken") String observedToken,
            @Param("observedEpoch") long observedEpoch, @Param("newEpoch") long newEpoch,
            @Param("errorCode") String errorCode);

}
