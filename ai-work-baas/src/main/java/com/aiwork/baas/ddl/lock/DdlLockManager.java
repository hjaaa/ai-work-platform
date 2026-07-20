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

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 第一层 DDL 串行锁(spec §9.2)：SET NX + TTL，value 为 owner_token；续租与释放均为 Lua 原子 compare，
 * watchdog 续租失败即标记丢锁。
 *
 * @author ai-work
 * @date 2026/07/18
 */
@Slf4j
public class DdlLockManager {

	private static final String RENEW_LUA = "if redis.call('GET', KEYS[1]) == ARGV[1] then "
			+ "return redis.call('PEXPIRE', KEYS[1], ARGV[2]) else return 0 end";

	private static final String RELEASE_LUA = "if redis.call('GET', KEYS[1]) == ARGV[1] then "
			+ "return redis.call('DEL', KEYS[1]) else return 0 end";

	private final StringRedisTemplate redisTemplate;

	private final long ttlMillis;

	private final long renewPeriodMillis;

	private final String instanceId = UUID.randomUUID().toString().substring(0, 8);

	private final DefaultRedisScript<Long> renewScript = new DefaultRedisScript<>(RENEW_LUA, Long.class);

	private final DefaultRedisScript<Long> releaseScript = new DefaultRedisScript<>(RELEASE_LUA, Long.class);

	private final ScheduledThreadPoolExecutor watchdog;

	public DdlLockManager(StringRedisTemplate redisTemplate, long ttlMillis, long renewPeriodMillis) {
		this(redisTemplate, ttlMillis, renewPeriodMillis, 4);
	}

	public DdlLockManager(StringRedisTemplate redisTemplate, long ttlMillis, long renewPeriodMillis,
			int watchdogThreads) {
		this.redisTemplate = redisTemplate;
		this.ttlMillis = ttlMillis;
		this.renewPeriodMillis = renewPeriodMillis;
		this.watchdog = createWatchdog(watchdogThreads);
	}

	public static String lockKey(Long projectId) {
		return "baas:ddl:" + projectId;
	}

	/**
	 * 尝试获取项目 DDL Redis 锁。
	 * @param projectId 项目 ID
	 * @return 取锁失败返回 null，由调用方映射为 409
	 */
	public LockHandle tryAcquire(Long projectId) {
		String ownerToken = instanceId + "-" + UUID.randomUUID();
		Boolean acquired = redisTemplate.opsForValue()
			.setIfAbsent(lockKey(projectId), ownerToken, Duration.ofMillis(ttlMillis));
		if (!Boolean.TRUE.equals(acquired)) {
			return null;
		}
		LockHandle handle = new LockHandle(projectId, ownerToken);
		try {
			handle.renewTask = watchdog.scheduleAtFixedRate(() -> renew(handle), renewPeriodMillis, renewPeriodMillis,
					TimeUnit.MILLISECONDS);
		}
		catch (RuntimeException exception) {
			release(handle);
			throw exception;
		}
		return handle;
	}

	public boolean stillHeld(LockHandle handle) {
		if (handle.lost()) {
			return false;
		}
		try {
			boolean held = handle.ownerToken().equals(redisTemplate.opsForValue().get(lockKey(handle.projectId())));
			if (!held) {
				loseLock(handle);
			}
			return held;
		}
		catch (RuntimeException exception) {
			log.warn("check ddl lock failed projectId={} errorType={}, marking lost", handle.projectId(),
					exception.getClass().getSimpleName());
			loseLock(handle);
			return false;
		}
	}

	public boolean isHeldBy(Long projectId, String ownerToken) {
		if (ownerToken == null) {
			return false;
		}
		return ownerToken.equals(redisTemplate.opsForValue().get(lockKey(projectId)));
	}

	public void release(LockHandle handle) {
		if (handle.renewTask != null) {
			handle.renewTask.cancel(false);
		}
		try {
			redisTemplate.execute(releaseScript, List.of(lockKey(handle.projectId())), handle.ownerToken());
		}
		catch (RuntimeException exception) {
			log.warn("release ddl lock failed projectId={} errorType={}", handle.projectId(),
					exception.getClass().getSimpleName());
		}
	}

	private void renew(LockHandle handle) {
		try {
			Long renewed = redisTemplate.execute(renewScript, List.of(lockKey(handle.projectId())), handle.ownerToken(),
					String.valueOf(ttlMillis));
			if (renewed == null || renewed == 0) {
				loseLock(handle);
			}
		}
		catch (RuntimeException exception) {
			log.warn("renew ddl lock failed projectId={} errorType={}, marking lost", handle.projectId(),
					exception.getClass().getSimpleName());
			loseLock(handle);
		}
	}

	private void loseLock(LockHandle handle) {
		handle.markLost();
		if (handle.renewTask != null) {
			handle.renewTask.cancel(false);
		}
	}

	private static ScheduledThreadPoolExecutor createWatchdog(int threads) {
		ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(Math.max(2, threads), runnable -> {
			Thread thread = new Thread(runnable, "baas-ddl-lock-watchdog");
			thread.setDaemon(true);
			return thread;
		}, new ThreadPoolExecutor.AbortPolicy());
		executor.setRemoveOnCancelPolicy(true);
		return executor;
	}

	@PreDestroy
	public void shutdown() {
		watchdog.shutdownNow();
	}

}
