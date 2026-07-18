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

package com.aiwork.baas.ddl.lock;

import java.util.concurrent.ScheduledFuture;

/**
 * 一次 Redis DDL 锁持有：owner_token 每次执行唯一(spec §9.2)。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public final class LockHandle {

	private final Long projectId;

	private final String ownerToken;

	private volatile boolean lost;

	volatile ScheduledFuture<?> renewTask;

	LockHandle(Long projectId, String ownerToken) {
		this.projectId = projectId;
		this.ownerToken = ownerToken;
	}

	public Long projectId() {
		return projectId;
	}

	public String ownerToken() {
		return ownerToken;
	}

	public boolean lost() {
		return lost;
	}

	void markLost() {
		this.lost = true;
	}

}
