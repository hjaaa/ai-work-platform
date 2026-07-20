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

package com.aiwork.baas.ddl.lock;

/**
 * 所有 Plan B 参与者共用的双层锁入口：Redis → GET_LOCK → 再验 Redis → 回调。 回调结束后先释放 advisory lock，最后
 * compare-and-delete Redis token。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public class ProjectDdlLockExecutor {

	private final DdlLockManager lockManager;

	private final AdvisoryLockTemplate advisoryLockTemplate;

	private final LockAcquisitionObserver observer;

	public ProjectDdlLockExecutor(DdlLockManager lockManager, AdvisoryLockTemplate advisoryLockTemplate,
			LockAcquisitionObserver observer) {
		this.lockManager = lockManager;
		this.advisoryLockTemplate = advisoryLockTemplate;
		this.observer = observer;
	}

	public <T> T execute(Long projectId, ProjectDdlLockCallback<T> callback) {
		LockHandle handle = lockManager.tryAcquire(projectId);
		if (handle == null) {
			throw new DdlLockBusyException("该项目有 DDL 操作进行中(Redis lock busy)");
		}
		boolean releaseRedis = true;
		try {
			observer.afterRedisBeforeAdvisory(handle);
			return advisoryLockTemplate.executeWithLock(projectId, connection -> {
				assertStillHeld(handle);
				T result = callback.doInLock(handle, connection);
				assertStillHeld(handle);
				return result;
			});
		}
		catch (RuntimeException | Error exception) {
			releaseRedis = !hasUncertainAdvisoryState(exception);
			throw exception;
		}
		finally {
			if (releaseRedis) {
				lockManager.release(handle);
			}
		}
	}

	private boolean hasUncertainAdvisoryState(Throwable failure) {
		if (failure instanceof DdlLockInfrastructureException infrastructureFailure
				&& infrastructureFailure.advisoryStateUncertain()) {
			return true;
		}
		for (Throwable suppressed : failure.getSuppressed()) {
			if (hasUncertainAdvisoryState(suppressed)) {
				return true;
			}
		}
		return false;
	}

	public void assertStillHeld(LockHandle handle) {
		if (!lockManager.stillHeld(handle)) {
			throw new DdlLockBusyException("Redis owner_token 已失效");
		}
	}

}
