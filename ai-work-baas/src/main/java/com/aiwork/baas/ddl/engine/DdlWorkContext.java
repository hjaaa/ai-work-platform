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

import com.aiwork.baas.ddl.inspect.SchemaInspector;
import com.aiwork.baas.ddl.lock.LockHandle;
import com.aiwork.baas.entity.BaasDdlLog;
import com.aiwork.baas.entity.enums.DdlStep;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.util.function.Supplier;

/**
 * DdlWork 的执行上下文:持锁连接、分支、所有权信息与检查点推进入口。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public class DdlWorkContext {

    private final DdlExecutionEngine engine;

    private final DdlOperationSpec spec;

    private final Connection connection;

    private final OwnershipBranch branch;

    private final BaasDdlLog existingLog;

    private final LockHandle lockHandle;

    private JdbcTemplate projectJdbc;

    private String ownerToken;

    private Long fenceEpoch;

    private Long logId;

    private DdlStep currentStep;

    DdlWorkContext(DdlExecutionEngine engine, DdlOperationSpec spec, Connection connection, OwnershipBranch branch,
            BaasDdlLog existingLog, LockHandle lockHandle) {
        this.engine = engine;
        this.spec = spec;
        this.connection = connection;
        this.branch = branch;
        this.existingLog = existingLog;
        this.lockHandle = lockHandle;
    }

    public DdlOperationSpec spec() {
        return spec;
    }

    public Connection projectConnection() {
        return connection;
    }

    /**
     * 持锁连接上的 JdbcTemplate(suppressClose;statement 超时 = DDL 专用超时,spec §13)。
     * @return 项目库 JDBC 操作入口
     */
    public JdbcTemplate projectJdbc() {
        if (projectJdbc == null) {
            projectJdbc = SchemaInspector.jdbcFor(connection);
            projectJdbc.setQueryTimeout(engine.ddlTimeoutSeconds());
        }
        return projectJdbc;
    }

    public OwnershipBranch branch() {
        return branch;
    }

    /**
     * 锁内重读到的既有日志行(NEW_OPERATION 为 null)。
     * @return 既有日志行
     */
    public BaasDdlLog existingLog() {
        return existingLog;
    }

    public String ownerToken() {
        return ownerToken;
    }

    public long fenceEpoch() {
        return fenceEpoch;
    }

    public Long logId() {
        return logId;
    }

    public DdlStep currentStep() {
        return currentStep;
    }

    public boolean stepReached(DdlStep step) {
        return currentStep != null && currentStep.reached(step);
    }

    /** 每个检查点推进前校验 Redis 锁仍为本 owner_token 持有(spec §9.2)。 */
    public void assertLockStillHeld() {
        if (!engine.lockStillHeld(lockHandle)) {
            throw new StaleExecutorException("Redis 锁丢失,中止后续步骤");
        }
    }

    /** 守卫检查点事务:项目行 FOR UPDATE 校验 epoch + 日志 step 条件更新。 */
    public void advanceToDdlApplied() {
        engine.advanceToDdlApplied(this);
    }

    /**
     * 守卫终态事务:项目行 FOR UPDATE 校验 epoch → 执行元数据写入并构建快照(事务内)→
     * METADATA_APPLIED/SUCCESS 终态条件更新,任一失败整笔回滚。
     * @param metadataWrites 在守卫事务内执行的平台元数据写入,返回 result_snapshot
     * @return 成功结果快照
     */
    public ObjectNode completeSuccess(Supplier<ObjectNode> metadataWrites) {
        return engine.completeSuccess(this, metadataWrites);
    }

    LockHandle lockHandle() {
        return lockHandle;
    }

    void setOwnership(String ownerToken, long fenceEpoch, Long logId, DdlStep step) {
        this.ownerToken = ownerToken;
        this.fenceEpoch = fenceEpoch;
        this.logId = logId;
        this.currentStep = step;
    }

    void setCurrentStep(DdlStep step) {
        this.currentStep = step;
    }

}
