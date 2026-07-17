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

package com.aiwork.baas.datasource;

import com.aiwork.baas.entity.BaasProject;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * BaaS 项目连接池注册表。Entry 通过 OPEN、DRAINING、CLOSED 状态机保护在途请求，
 * 并通过池数量上限和全局连接许可双重约束资源使用。
 *
 * @author ai-work
 * @date 2026/07/17
 */
@Slf4j
public class ProjectDataSourceRegistry {

    private enum State {

        OPEN, DRAINING, CLOSED

    }

    private final class Entry {

        private final String projectRef;

        private final DataSource dataSource;

        private final AtomicInteger activeCount = new AtomicInteger();

        private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);

        private volatile long lastAccessTime = System.nanoTime();

        private Entry(String projectRef, DataSource dataSource) {
            this.projectRef = projectRef;
            this.dataSource = dataSource;
        }

        private synchronized boolean tryAcquire() {
            if (state.get() != State.OPEN) {
                return false;
            }

            activeCount.incrementAndGet();
            lastAccessTime = System.nanoTime();
            return true;
        }

        private synchronized boolean tryDrainIfIdle() {
            if (state.get() != State.OPEN || activeCount.get() != 0) {
                return false;
            }

            state.set(State.DRAINING);
            return true;
        }

        private synchronized void startDrain() {
            state.compareAndSet(State.OPEN, State.DRAINING);
        }

        private void release() {
            activeCount.decrementAndGet();
            closeIfDrained(this);
        }

    }

    private final Map<String, Entry> pools = new ConcurrentHashMap<>();

    private final Set<String> blockedProjectRefs = ConcurrentHashMap.newKeySet();

    private final Function<BaasProject, DataSource> factory;

    private final Consumer<String> beforeIdleDrain;

    private final int perPoolMax;

    private final Semaphore poolCapacity;

    private final Semaphore globalBudget;

    private final Object capacityLock = new Object();

    public ProjectDataSourceRegistry(Function<BaasProject, DataSource> factory, int maxPools, int perPoolMax,
            int globalMaxConnections) {
        this(factory, maxPools, perPoolMax, globalMaxConnections, projectRef -> {
        });
    }

    ProjectDataSourceRegistry(Function<BaasProject, DataSource> factory, int maxPools, int perPoolMax,
            int globalMaxConnections, Consumer<String> beforeIdleDrain) {
        this.factory = factory;
        this.beforeIdleDrain = beforeIdleDrain;
        this.perPoolMax = perPoolMax;
        this.poolCapacity = new Semaphore(maxPools);
        this.globalBudget = new Semaphore(globalMaxConnections);
    }

    /**
     * 在项目连接池借用期内执行操作。
     * @param project 已鉴权的项目
     * @param action 数据源操作
     * @param <T> 返回值类型
     * @return 操作结果
     */
    public <T> T execute(BaasProject project, Function<DataSource, T> action) {
        String projectRef = project.getProjectRef();
        for (;;) {
            if (blockedProjectRefs.contains(projectRef)) {
                throw blockedException(projectRef);
            }

            Entry entry = pools.get(projectRef);
            if (entry == null) {
                entry = createIfAbsent(project);
            }
            if (entry.tryAcquire()) {
                if (blockedProjectRefs.contains(projectRef)) {
                    entry.release();
                    throw blockedException(projectRef);
                }
                try {
                    return action.apply(entry.dataSource);
                }
                finally {
                    entry.release();
                }
            }

            pools.remove(projectRef, entry);
        }
    }

    /**
     * 永久阻断项目的新借用，并在所有在途操作结束后关闭连接池。
     * @param projectRef 项目引用
     */
    public void blockAndDrain(String projectRef) {
        synchronized (capacityLock) {
            blockedProjectRefs.add(projectRef);
            Entry entry = pools.remove(projectRef);
            if (entry != null) {
                drain(entry);
            }
        }
    }

    private Entry createIfAbsent(BaasProject project) {
        String projectRef = project.getProjectRef();
        synchronized (capacityLock) {
            Entry existing = pools.get(projectRef);
            if (existing != null) {
                return existing;
            }
            if (blockedProjectRefs.contains(projectRef)) {
                throw blockedException(projectRef);
            }

            evictIdleUntilCapacity();
            if (!globalBudget.tryAcquire(perPoolMax)) {
                poolCapacity.release();
                throw new IllegalStateException("global connection budget exhausted");
            }
            try {
                Entry entry = new Entry(projectRef, factory.apply(project));
                pools.put(projectRef, entry);
                return entry;
            }
            catch (RuntimeException | Error exception) {
                globalBudget.release(perPoolMax);
                poolCapacity.release();
                throw exception;
            }
        }
    }

    /**
     * 获取建池容量；无空闲容量时淘汰空闲 LRU 并重试。调用方必须持有 capacityLock。
     */
    private void evictIdleUntilCapacity() {
        while (!poolCapacity.tryAcquire()) {
            Entry victim = pools.values().stream()
                .filter(entry -> entry.state.get() == State.OPEN && entry.activeCount.get() == 0)
                .min(Comparator.comparingLong(entry -> entry.lastAccessTime))
                .orElse(null);
            if (victim == null) {
                throw new IllegalStateException("maxPools capacity exhausted");
            }

            beforeIdleDrain.accept(victim.projectRef);
            if (!victim.tryDrainIfIdle()) {
                continue;
            }

            pools.remove(victim.projectRef, victim);
            closeIfDrained(victim);
        }
    }

    private void drain(Entry entry) {
        entry.startDrain();
        closeIfDrained(entry);
    }

    private void closeIfDrained(Entry entry) {
        if (entry.activeCount.get() == 0 && entry.state.compareAndSet(State.DRAINING, State.CLOSED)) {
            try {
                closeDataSource(entry);
            }
            finally {
                globalBudget.release(perPoolMax);
                poolCapacity.release();
            }
        }
    }

    private void closeDataSource(Entry entry) {
        if (entry.dataSource instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            }
            catch (Exception exception) {
                log.warn("close pool {} failed", entry.projectRef, exception);
            }
        }
    }

    private IllegalStateException blockedException(String projectRef) {
        return new IllegalStateException("project pool is blocked: " + projectRef);
    }

}
