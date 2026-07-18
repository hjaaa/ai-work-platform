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

package com.aiwork.baas.ddl.engine;

import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.mapper.BaasProjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 项目级单调 fencing(spec §9.2):所有权事务内递增 epoch,其后一切平台事务
 * FOR UPDATE 锁项目行并校验 epoch 相等,不匹配抛 StaleExecutorException 触发整笔回滚。
 *
 * @author ai-work
 * @date 2026/07/18
 */
@Component
@RequiredArgsConstructor
public class DdlFencingGuard {

    private final BaasProjectMapper projectMapper;

    /**
     * @param projectId 项目 ID
     * @return 递增后的新 epoch
     */
    public long incrementEpochInTx(Long projectId) {
        requireActiveTransaction();
        BaasProject project = projectMapper.selectByIdForUpdate(projectId);
        if (project == null) {
            throw new StaleExecutorException("项目不存在,无法取得 DDL 所有权");
        }
        long oldEpoch = project.getDdlFenceEpoch() == null ? 0L : project.getDdlFenceEpoch();
        if (projectMapper.bumpFenceEpoch(projectId, oldEpoch) != 1) {
            throw new StaleExecutorException("项目 epoch 并发变更,放弃本次执行");
        }
        return oldEpoch + 1;
    }

    /**
     * @param projectId 项目 ID
     * @param expectedEpoch 预期 fencing epoch
     * @return FOR UPDATE 锁定后的项目行(校验通过)
     */
    public BaasProject verifyEpochInTx(Long projectId, long expectedEpoch) {
        requireActiveTransaction();
        BaasProject project = projectMapper.selectByIdForUpdate(projectId);
        if (project == null || project.getDdlFenceEpoch() == null
                || project.getDdlFenceEpoch() != expectedEpoch) {
            throw new StaleExecutorException("项目 epoch 已推进,本执行者陈旧,整笔回滚");
        }
        return project;
    }

    private void requireActiveTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("fencing guard must run inside a platform transaction");
        }
    }

}
