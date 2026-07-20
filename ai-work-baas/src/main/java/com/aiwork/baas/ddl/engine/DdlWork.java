/*
 *
 *      Copyright (c) 2018-2026, lengleng All rights reserved.
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

package com.aiwork.baas.ddl.engine;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 具体 Schema 操作策略,由 DdlExecutionEngine 按 spec §9.2 固定顺序驱动。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public interface DdlWork {

    /**
     * 双层锁内、所有权取得前:按 context.branch() 重读现状并校验该分支允许的状态集
     * (spec §9.2:各分支合法状态相反,不能统一前置)。失败抛异常,不产生日志。
     */
    void validateInLock(DdlWorkContext context) throws Exception;

    /**
     * 所有权短事务内、日志 INSERT/CAS 之后:表状态置位(CREATING/ALTERING)等平台库写入,
     * 与所有权同事务提交,必须在任何项目库副作用之前(spec §9.2)。
     */
    default void inOwnershipTx(DdlWorkContext context) {
    }

    /**
     * 所有权确立后的执行体:经 context.advanceToDdlApplied()/completeSuccess(...) 推进检查点。
     * @param context DDL 工作上下文
     * @return result_snapshot
     * @throws Exception 工作执行失败
     */
    ObjectNode perform(DdlWorkContext context) throws Exception;

    /**
     * perform 失败时与日志 FAILED 终态同一守卫事务执行(表状态按操作类型落位)。
     */
    default void onFailureTx(DdlWorkContext context) {
    }

}
