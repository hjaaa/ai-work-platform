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
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;

/**
 * Schema 操作日志 Mapper。
 *
 * @author ai-work
 * @date 2026/07/17
 */
@Mapper
public interface BaasDdlLogMapper extends BaseMapper<BaasDdlLog> {

    /**
     * 按项目与幂等键查询完整日志。
     * @param projectId 项目 ID
     * @param operationId 操作 ID
     * @return 日志，不存在返回 null
     */
    @Select("SELECT id, operation_id, project_id, operation_type, table_name, table_id, request_hash, "
            + "result_snapshot, owner_token, fence_epoch, trigger_source, ddl_text, step, status, error_msg, "
            + "retry_count, create_time, update_time FROM baas_ddl_log "
            + "WHERE project_id = #{projectId} AND operation_id = #{operationId}")
    @Results(id = "baasDdlLogResult", value = {
            @Result(column = "id", property = "id", id = true),
            @Result(column = "operation_id", property = "operationId"),
            @Result(column = "project_id", property = "projectId"),
            @Result(column = "operation_type", property = "operationType"),
            @Result(column = "table_name", property = "tableName"),
            @Result(column = "table_id", property = "tableId"),
            @Result(column = "request_hash", property = "requestHash"),
            @Result(column = "result_snapshot", property = "resultSnapshot"),
            @Result(column = "owner_token", property = "ownerToken"),
            @Result(column = "fence_epoch", property = "fenceEpoch"),
            @Result(column = "trigger_source", property = "triggerSource"),
            @Result(column = "ddl_text", property = "ddlText"),
            @Result(column = "step", property = "step"),
            @Result(column = "status", property = "status"),
            @Result(column = "error_msg", property = "errorMsg"),
            @Result(column = "retry_count", property = "retryCount"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_time", property = "updateTime") })
    BaasDdlLog selectByProjectAndOperation(@Param("projectId") Long projectId,
            @Param("operationId") String operationId);

    /**
     * FAILED 重试分支，并发重试有且仅有一个成功。
     * @param logId 日志 ID
     * @param observedToken 读取到的旧 token，可为 null
     * @param newToken 新执行者 token
     * @param newEpoch 新 fencing epoch
     * @return 1 表示认领成功，0 表示条件竞争失败
     */
    @Update("UPDATE baas_ddl_log SET owner_token = #{newToken}, status = 'RUNNING', "
            + "retry_count = retry_count + 1, fence_epoch = #{newEpoch} "
            + "WHERE id = #{logId} AND owner_token = #{observedToken} AND status = 'FAILED'")
    int casRetryFailed(@Param("logId") Long logId, @Param("observedToken") String observedToken,
            @Param("newToken") String newToken, @Param("newEpoch") long newEpoch);

    /**
     * 陈旧 RUNNING 接管分支。
     * @param logId 日志 ID
     * @param observedToken 读取到的旧 token
     * @param newToken 新执行者 token
     * @param newEpoch 新 fencing epoch
     * @return 1 表示接管成功，0 表示条件竞争失败
     */
    @Update("UPDATE baas_ddl_log SET owner_token = #{newToken}, status = 'RUNNING', fence_epoch = #{newEpoch} "
            + "WHERE id = #{logId} AND owner_token = #{observedToken} AND status = 'RUNNING'")
    int casTakeOverRunning(@Param("logId") Long logId, @Param("observedToken") String observedToken,
            @Param("newToken") String newToken, @Param("newEpoch") long newEpoch);

    /**
     * PENDING cleanup 认领分支。
     * @param logId 日志 ID
     * @param newToken 新执行者 token
     * @param newEpoch 新 fencing epoch
     * @return 1 表示认领成功，0 表示条件竞争失败
     */
    @Update("UPDATE baas_ddl_log SET owner_token = #{newToken}, status = 'RUNNING', fence_epoch = #{newEpoch} "
            + "WHERE id = #{logId} AND status = 'PENDING' AND owner_token IS NULL")
    int casClaimPending(@Param("logId") Long logId, @Param("newToken") String newToken,
            @Param("newEpoch") long newEpoch);

    /**
     * 检查点推进，RUNNING fencing 条件更新(spec §9.2)。
     * @param logId 日志 ID
     * @param ownerToken 当前执行者 token
     * @param step 目标检查点
     * @return 1 表示成功，0 表示执行者已陈旧
     */
    @Update("UPDATE baas_ddl_log SET step = #{step} "
            + "WHERE id = #{logId} AND owner_token = #{ownerToken} AND status = 'RUNNING'")
    int advanceStepGuarded(@Param("logId") Long logId, @Param("ownerToken") String ownerToken,
            @Param("step") String step);

    /**
     * 终态写入，owner_token + RUNNING + fence_epoch 三重守卫。
     * @param logId 日志 ID
     * @param ownerToken 当前执行者 token
     * @param fenceEpoch 当前 fencing epoch
     * @param status 终态
     * @param step 终态检查点
     * @param resultSnapshot 成功快照
     * @param errorMsg 稳定错误码
     * @return 1 表示成功，0 表示执行者已陈旧
     */
    @Update("UPDATE baas_ddl_log SET status = #{status}, step = #{step}, "
            + "result_snapshot = #{resultSnapshot}, error_msg = #{errorMsg} "
            + "WHERE id = #{logId} AND owner_token = #{ownerToken} AND status = 'RUNNING' "
            + "AND fence_epoch = #{fenceEpoch}")
    int finishGuarded(@Param("logId") Long logId, @Param("ownerToken") String ownerToken,
            @Param("fenceEpoch") long fenceEpoch, @Param("status") String status, @Param("step") String step,
            @Param("resultSnapshot") String resultSnapshot, @Param("errorMsg") String errorMsg);

    /**
     * 失败终态只更新状态与稳定错误码，保留数据库中已提交的检查点，保证 step 单调。
     * @param logId 日志 ID
     * @param ownerToken 当前执行者 token
     * @param fenceEpoch 当前项目 epoch
     * @param errorMsg 已脱敏稳定错误码
     * @return 1 表示成功，0 表示执行者已陈旧
     */
    @Update("UPDATE baas_ddl_log SET status = 'FAILED', result_snapshot = NULL, error_msg = #{errorMsg} "
            + "WHERE id = #{logId} AND owner_token = #{ownerToken} AND status = 'RUNNING' "
            + "AND fence_epoch = #{fenceEpoch}")
    int failGuarded(@Param("logId") Long logId, @Param("ownerToken") String ownerToken,
            @Param("fenceEpoch") long fenceEpoch, @Param("errorMsg") String errorMsg);

    /**
     * HTTP 陈旧 RUNNING 兜底：旧 owner_token 与 fence_epoch 双条件 CAS 置 FAILED。
     * @param logId 日志 ID
     * @param observedToken 观察到的旧 token
     * @param observedEpoch 观察到的旧 epoch
     * @param newEpoch 本次 fencing epoch
     * @param errorCode 稳定错误码
     * @return 1 表示成功，0 表示条件竞争失败
     */
    @Update("UPDATE baas_ddl_log SET status = 'FAILED', fence_epoch = #{newEpoch}, error_msg = #{errorCode} "
            + "WHERE id = #{logId} AND owner_token = #{observedToken} AND fence_epoch = #{observedEpoch} "
            + "AND status = 'RUNNING'")
    int casForceFailRunning(@Param("logId") Long logId, @Param("observedToken") String observedToken,
            @Param("observedEpoch") long observedEpoch, @Param("newEpoch") long newEpoch,
            @Param("errorCode") String errorCode);

    /**
     * 项目库已物理删除时，将遗留 cleanup 任务安全收敛为 no-op SUCCESS。
     * @param projectId 项目 ID
     * @param snapshot 最终结果快照
     * @return 收敛的日志行数
     */
    @Update("UPDATE baas_ddl_log SET status = 'SUCCESS', step = 'METADATA_APPLIED', "
            + "result_snapshot = #{snapshot}, error_msg = NULL WHERE project_id = #{projectId} "
            + "AND operation_type = 'cleanup-drop' AND status <> 'SUCCESS'")
    int finishCleanupForDeletedProject(@Param("projectId") Long projectId,
            @Param("snapshot") String snapshot);

}
