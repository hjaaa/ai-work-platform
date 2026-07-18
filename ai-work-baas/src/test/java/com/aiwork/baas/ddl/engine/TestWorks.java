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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Task 7/16 共用的最小 DdlWork 测试工具。 */
public final class TestWorks {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TestWorks() {
    }

    /** 最小成功 work:无项目库副作用,快照记录执行次数与分支。 */
    public static final class RecordingWork implements DdlWork {

        public final AtomicInteger performCount = new AtomicInteger();

        public final AtomicBoolean failPerform = new AtomicBoolean(false);

        public final AtomicBoolean failOwnershipTx = new AtomicBoolean(false);

        public volatile OwnershipBranch observedBranch;

        public volatile boolean failureTxCalled;

        public volatile long pauseInPerformMillis;

        @Override
        public void validateInLock(DdlWorkContext context) {
            observedBranch = context.branch();
        }

        @Override
        public void inOwnershipTx(DdlWorkContext context) {
            if (failOwnershipTx.get()) {
                throw new IllegalStateException("ownership tx failure");
            }
        }

        @Override
        public ObjectNode perform(DdlWorkContext context) throws Exception {
            if (pauseInPerformMillis > 0) {
                Thread.sleep(pauseInPerformMillis);
            }
            performCount.incrementAndGet();
            if (failPerform.get()) {
                throw new IllegalStateException("perform failure");
            }
            context.advanceToDdlApplied();
            return context.completeSuccess(() -> MAPPER.createObjectNode().put("performs", performCount.get()));
        }

        @Override
        public void onFailureTx(DdlWorkContext context) {
            failureTxCalled = true;
        }

    }

}
