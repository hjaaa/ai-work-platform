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
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 项目连接池注册表单元测试。
 *
 * @author ai-work
 * @date 2026/07/17
 */
class ProjectDataSourceRegistryTest {

    private static final long TIMEOUT_SECONDS = 5L;

    /**
     * 可关闭的数据源桩，记录关闭状态。
     */
    static class StubDataSource implements DataSource, AutoCloseable {

        final AtomicBoolean closed = new AtomicBoolean(false);

        final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            closed.set(true);
        }

        @Override
        public Connection getConnection() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Connection getConnection(String username, String password) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter output) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return null;
        }

        @Override
        public <T> T unwrap(Class<T> interfaceType) {
            return null;
        }

        @Override
        public boolean isWrapperFor(Class<?> interfaceType) {
            return false;
        }

    }

    static class RetriableCloseDataSource extends StubDataSource {

        private final AtomicInteger remainingFailures;

        RetriableCloseDataSource(int failureCount) {
            this.remainingFailures = new AtomicInteger(failureCount);
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            if (remainingFailures.getAndDecrement() > 0) {
                throw new IllegalStateException("test close failure before physical close");
            }
            closed.set(true);
        }

    }

    static class BlockingCloseDataSource extends StubDataSource {

        private final CountDownLatch closeStarted;

        private final CountDownLatch allowClose;

        BlockingCloseDataSource(CountDownLatch closeStarted, CountDownLatch allowClose) {
            this.closeStarted = closeStarted;
            this.allowClose = allowClose;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            closeStarted.countDown();
            await(allowClose);
            closed.set(true);
        }

    }

    private static BaasProject project(String projectRef) {
        BaasProject project = new BaasProject();
        project.setProjectRef(projectRef);
        return project;
    }

    private static ExecutorService executor(int threadCount, String threadNamePrefix) {
        AtomicInteger threadNumber = new AtomicInteger(1);
        return new ThreadPoolExecutor(threadCount, threadCount, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(threadCount), task -> {
                    Thread thread = new Thread(task);
                    thread.setName(threadNamePrefix + threadNumber.getAndIncrement());
                    return thread;
                });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for test latch");
            }
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for test latch", exception);
        }
    }

    private static void awaitThreadState(Thread thread, Thread.State expectedState) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            Thread.State currentState = thread.getState();
            if (currentState == expectedState) {
                return;
            }
            if (currentState == Thread.State.TERMINATED) {
                throw new AssertionError("test thread terminated before reaching " + expectedState);
            }
            Thread.yield();
        }

        throw new AssertionError(
                "timed out waiting for thread state " + expectedState + ", actual=" + thread.getState());
    }

    @Test
    void createsPoolOnceUnderConcurrency() throws Exception {
        AtomicInteger factoryCalls = new AtomicInteger();
        ProjectDataSourceRegistry registry = new ProjectDataSourceRegistry(project -> {
            factoryCalls.incrementAndGet();
            return new StubDataSource();
        }, 4, 10, 100);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = executor(8, "registry-create-");
        List<Future<?>> results = new ArrayList<>(8);

        try {
            for (int i = 0; i < 8; i++) {
                results.add(executor.submit(() -> {
                    await(start);
                    return registry.execute(project("aaaa"), dataSource -> null);
                }));
            }
            start.countDown();
            for (Future<?> result : results) {
                result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        }
        finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(factoryCalls.get()).isEqualTo(1);
    }

    @Test
    void evictsIdleLruWhenExceedingMaxPools() {
        Map<String, StubDataSource> created = new java.util.concurrent.ConcurrentHashMap<>();
        ProjectDataSourceRegistry registry = new ProjectDataSourceRegistry(
                project -> created.computeIfAbsent(project.getProjectRef(), key -> new StubDataSource()), 2, 10, 100);

        registry.execute(project("a"), dataSource -> null);
        registry.execute(project("b"), dataSource -> null);
        registry.execute(project("c"), dataSource -> null);

        assertThat(created.get("a").closed.get()).isTrue();
        assertThat(created.get("b").closed.get()).isFalse();
    }

    @Test
    void activePoolIsNotClosedUntilReleased() throws Exception {
        Map<String, StubDataSource> created = new java.util.concurrent.ConcurrentHashMap<>();
        ProjectDataSourceRegistry registry = new ProjectDataSourceRegistry(
                project -> created.computeIfAbsent(project.getProjectRef(), key -> new StubDataSource()), 10, 10, 100);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch releaseNow = new CountDownLatch(1);
        ExecutorService executor = executor(1, "registry-active-");

        try {
            Future<?> borrower = executor.submit(() -> registry.execute(project("a"), dataSource -> {
                inside.countDown();
                await(releaseNow);
                return null;
            }));
            await(inside);

            registry.blockAndDrain("a");
            assertThat(created.get("a").closed.get()).isFalse();

            releaseNow.countDown();
            borrower.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(created.get("a").closed.get()).isTrue();
        }
        finally {
            releaseNow.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void globalConnectionBudgetIsEnforced() {
        ProjectDataSourceRegistry registry = new ProjectDataSourceRegistry(project -> new StubDataSource(), 10, 10, 25);
        registry.execute(project("a"), dataSource -> null);
        registry.execute(project("b"), dataSource -> null);

        assertThatThrownBy(() -> registry.execute(project("c"), dataSource -> null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("budget");
    }

    @Test
    void factoryFailuresReleasePoolAndGlobalCapacity() {
        AtomicInteger factoryCalls = new AtomicInteger();
        ProjectDataSourceRegistry registry = new ProjectDataSourceRegistry(project -> {
            factoryCalls.incrementAndGet();
            if ("runtime".equals(project.getProjectRef())) {
                throw new IllegalStateException("runtime factory failure");
            }
            if ("error".equals(project.getProjectRef())) {
                throw new AssertionError("error factory failure");
            }
            return new StubDataSource();
        }, 1, 10, 10);

        assertThatThrownBy(() -> registry.execute(project("runtime"), dataSource -> null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("runtime factory failure");
        assertThatThrownBy(() -> registry.execute(project("error"), dataSource -> null))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("error factory failure");

        registry.execute(project("healthy"), dataSource -> null);
        assertThat(factoryCalls.get()).isEqualTo(3);
    }

    @Test
    void maxPoolsRejectsCreationWhenAllPoolsAreActive() throws Exception {
        Map<String, StubDataSource> created = new java.util.concurrent.ConcurrentHashMap<>();
        AtomicInteger factoryCalls = new AtomicInteger();
        ProjectDataSourceRegistry registry = new ProjectDataSourceRegistry(project -> {
            factoryCalls.incrementAndGet();
            return created.computeIfAbsent(project.getProjectRef(), key -> new StubDataSource());
        }, 1, 10, 100);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch releaseNow = new CountDownLatch(1);
        ExecutorService executor = executor(1, "registry-capacity-");

        try {
            Future<?> borrower = executor.submit(() -> registry.execute(project("a"), dataSource -> {
                inside.countDown();
                await(releaseNow);
                return null;
            }));
            await(inside);

            assertThatThrownBy(() -> registry.execute(project("b"), dataSource -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maxPools");
            assertThat(factoryCalls.get()).isEqualTo(1);
            assertThat(created.get("a").closed.get()).isFalse();

            releaseNow.countDown();
            borrower.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            registry.execute(project("a"), dataSource -> null);
            assertThat(factoryCalls.get()).isEqualTo(1);
            assertThat(created.get("a").closed.get()).isFalse();
        }
        finally {
            releaseNow.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void blockedRefRejectsNewBorrowAndNeverRebuilds() {
        AtomicInteger factoryCalls = new AtomicInteger();
        ProjectDataSourceRegistry registry = new ProjectDataSourceRegistry(project -> {
            factoryCalls.incrementAndGet();
            return new StubDataSource();
        }, 4, 10, 100);
        registry.execute(project("a"), dataSource -> null);

        registry.blockAndDrain("a");

        assertThatThrownBy(() -> registry.execute(project("a"), dataSource -> null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("blocked");
        assertThat(factoryCalls.get()).isEqualTo(1);
    }

    @Test
    void closeFailureKeepsPoolCapacityUntilSuccessfulRetry() {
        Map<String, StubDataSource> created = new java.util.concurrent.ConcurrentHashMap<>();
        AtomicInteger factoryCalls = new AtomicInteger();
        ProjectDataSourceRegistry registry = new ProjectDataSourceRegistry(project -> {
            factoryCalls.incrementAndGet();
            return created.computeIfAbsent(project.getProjectRef(), key -> "a".equals(key)
                    ? new RetriableCloseDataSource(1) : new StubDataSource());
        }, 1, 10, 20);
        registry.execute(project("a"), dataSource -> null);

        registry.blockAndDrain("a");

        assertThat(created.get("a").closeCalls.get()).isEqualTo(1);
        assertThat(created.get("a").closed.get()).isFalse();
        assertThatThrownBy(() -> registry.execute(project("b"), dataSource -> null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("maxPools");
        assertThat(factoryCalls.get()).isEqualTo(1);

        registry.blockAndDrain("a");
        registry.blockAndDrain("a");
        registry.execute(project("b"), dataSource -> null);

        assertThat(created.get("a").closed.get()).isTrue();
        assertThat(created.get("a").closeCalls.get()).isEqualTo(2);
        assertThat(factoryCalls.get()).isEqualTo(2);
    }

    @Test
    void closeFailureKeepsGlobalBudgetUntilSuccessfulRetry() {
        Map<String, StubDataSource> created = new java.util.concurrent.ConcurrentHashMap<>();
        AtomicInteger factoryCalls = new AtomicInteger();
        ProjectDataSourceRegistry registry = new ProjectDataSourceRegistry(project -> {
            factoryCalls.incrementAndGet();
            return created.computeIfAbsent(project.getProjectRef(), key -> "a".equals(key)
                    ? new RetriableCloseDataSource(1) : new StubDataSource());
        }, 2, 10, 10);
        registry.execute(project("a"), dataSource -> null);

        registry.blockAndDrain("a");

        assertThatThrownBy(() -> registry.execute(project("b"), dataSource -> null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("budget");
        assertThat(factoryCalls.get()).isEqualTo(1);

        registry.blockAndDrain("a");
        registry.execute(project("b"), dataSource -> null);

        assertThat(created.get("a").closed.get()).isTrue();
        assertThat(created.get("a").closeCalls.get()).isEqualTo(2);
        assertThat(factoryCalls.get()).isEqualTo(2);
    }

    @Test
    void lruCloseFailureKeepsProjectRefIsolated() {
        AtomicInteger projectFactoryCalls = new AtomicInteger();
        ProjectDataSourceRegistry registry = new ProjectDataSourceRegistry(project -> {
            if ("a".equals(project.getProjectRef())) {
                return projectFactoryCalls.incrementAndGet() == 1
                        ? new RetriableCloseDataSource(1) : new StubDataSource();
            }
            return new StubDataSource();
        }, 2, 10, 30);
        registry.execute(project("a"), dataSource -> null);
        registry.execute(project("b"), dataSource -> null);
        registry.execute(project("c"), dataSource -> null);

        assertThatThrownBy(() -> registry.execute(project("a"), dataSource -> null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("draining");
        assertThat(projectFactoryCalls.get()).isEqualTo(1);

        registry.retryClose("a");
        registry.execute(project("a"), dataSource -> null);

        assertThat(projectFactoryCalls.get()).isEqualTo(2);
    }

    @Test
    void closeAllRetriesBoundedlyAndClosesEveryRegisteredPool() {
        Map<String, StubDataSource> created = new java.util.concurrent.ConcurrentHashMap<>();
        ProjectDataSourceRegistry registry = new ProjectDataSourceRegistry(project -> created.computeIfAbsent(
                project.getProjectRef(), key -> "a".equals(key)
                        ? new RetriableCloseDataSource(2) : new StubDataSource()), 2, 10, 20);
        registry.execute(project("a"), dataSource -> null);
        registry.execute(project("b"), dataSource -> null);

        registry.closeAll();

        assertThat(created.get("a").closed.get()).isTrue();
        assertThat(created.get("a").closeCalls.get()).isEqualTo(3);
        assertThat(created.get("b").closed.get()).isTrue();
        assertThat(created.get("b").closeCalls.get()).isEqualTo(1);
    }

    @Test
    void closeAllRejectsNewProjectFirstBorrowOnceShutdownBegins() throws Exception {
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch allowClose = new CountDownLatch(1);
        CountDownLatch newBorrowStarted = new CountDownLatch(1);
        AtomicInteger factoryCalls = new AtomicInteger();
        BlockingCloseDataSource firstDataSource = new BlockingCloseDataSource(closeStarted, allowClose);
        ProjectDataSourceRegistry registry = new ProjectDataSourceRegistry(project -> {
            factoryCalls.incrementAndGet();
            return "a".equals(project.getProjectRef()) ? firstDataSource : new StubDataSource();
        }, 2, 10, 20);
        registry.execute(project("a"), dataSource -> null);
        ExecutorService executor = executor(2, "registry-close-all-");

        try {
            Future<?> closeAll = executor.submit(registry::closeAll);
            await(closeStarted);
            Future<?> newBorrow = executor.submit(() -> {
                newBorrowStarted.countDown();
                return registry.execute(project("b"), dataSource -> null);
            });
            await(newBorrowStarted);

            allowClose.countDown();
            closeAll.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThatThrownBy(() -> newBorrow.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("project pool registry is closed");
            assertThatThrownBy(() -> registry.execute(project("c"), dataSource -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("project pool registry is closed");
            assertThat(factoryCalls.get()).isEqualTo(1);
            assertThat(firstDataSource.closed.get()).isTrue();
        }
        finally {
            allowClose.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void closeAllRejectsCreateThatPassedFastCheckBeforeShutdownLock() throws Exception {
        CountDownLatch newBorrowStarted = new CountDownLatch(1);
        AtomicInteger factoryCalls = new AtomicInteger();
        AtomicReference<ProjectDataSourceRegistry> registryReference = new AtomicReference<>();
        AtomicReference<Thread> newBorrowThread = new AtomicReference<>();
        AtomicReference<Future<?>> newBorrowFuture = new AtomicReference<>();
        ExecutorService executor = executor(1, "registry-close-race-");
        ProjectDataSourceRegistry registry = new ProjectDataSourceRegistry(project -> {
            factoryCalls.incrementAndGet();
            return new StubDataSource();
        }, 1, 10, 20, projectRef -> {
            newBorrowFuture.set(executor.submit(() -> {
                newBorrowThread.set(Thread.currentThread());
                newBorrowStarted.countDown();
                return registryReference.get().execute(project("b"), dataSource -> null);
            }));
            await(newBorrowStarted);
            awaitThreadState(newBorrowThread.get(), Thread.State.BLOCKED);
            registryReference.get().closeAll();
        });
        registryReference.set(registry);
        registry.execute(project("a"), dataSource -> null);

        try {
            assertThatThrownBy(() -> registry.execute(project("c"), dataSource -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("project pool registry is closed");
            assertThatThrownBy(() -> newBorrowFuture.get().get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("project pool registry is closed");
            assertThat(factoryCalls.get()).isEqualTo(1);
        }
        finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void drainingPoolOccupiesMaxPoolsUntilItIsClosed() throws Exception {
        Map<String, StubDataSource> created = new java.util.concurrent.ConcurrentHashMap<>();
        AtomicInteger factoryCalls = new AtomicInteger();
        ProjectDataSourceRegistry registry = new ProjectDataSourceRegistry(project -> {
            factoryCalls.incrementAndGet();
            return created.computeIfAbsent(project.getProjectRef(), key -> new StubDataSource());
        }, 1, 10, 100);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch releaseNow = new CountDownLatch(1);
        ExecutorService executor = executor(1, "registry-draining-capacity-");

        try {
            Future<?> borrower = executor.submit(() -> registry.execute(project("a"), dataSource -> {
                inside.countDown();
                await(releaseNow);
                return null;
            }));
            await(inside);
            registry.blockAndDrain("a");
            assertThat(created.get("a").closed.get()).isFalse();

            assertThatThrownBy(() -> registry.execute(project("b"), dataSource -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maxPools");
            assertThat(factoryCalls.get()).isEqualTo(1);

            releaseNow.countDown();
            borrower.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(created.get("a").closed.get()).isTrue();

            registry.execute(project("b"), dataSource -> null);
            assertThat(factoryCalls.get()).isEqualTo(2);
        }
        finally {
            releaseNow.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void idleLruIsEvictedWhenAnotherPoolIsDraining() throws Exception {
        Map<String, StubDataSource> created = new java.util.concurrent.ConcurrentHashMap<>();
        ProjectDataSourceRegistry registry = new ProjectDataSourceRegistry(
                project -> created.computeIfAbsent(project.getProjectRef(), key -> new StubDataSource()), 2, 10, 100);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch releaseNow = new CountDownLatch(1);
        ExecutorService executor = executor(1, "registry-mixed-capacity-");

        try {
            Future<?> borrower = executor.submit(() -> registry.execute(project("a"), dataSource -> {
                inside.countDown();
                await(releaseNow);
                return null;
            }));
            await(inside);
            registry.execute(project("b"), dataSource -> null);
            registry.blockAndDrain("a");

            registry.execute(project("c"), dataSource -> null);

            assertThat(created.get("a").closed.get()).isFalse();
            assertThat(created.get("b").closed.get()).isTrue();
            assertThat(created.get("c").closed.get()).isFalse();

            releaseNow.countDown();
            borrower.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(created.get("a").closed.get()).isTrue();
        }
        finally {
            releaseNow.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void lruSkipsCandidateThatBecomesActiveBeforeDrain() throws Exception {
        Map<String, StubDataSource> created = new java.util.concurrent.ConcurrentHashMap<>();
        AtomicInteger factoryCalls = new AtomicInteger();
        AtomicBoolean gateFirstCandidate = new AtomicBoolean(true);
        CountDownLatch candidateSelected = new CountDownLatch(1);
        CountDownLatch borrowerInside = new CountDownLatch(1);
        CountDownLatch releaseBorrower = new CountDownLatch(1);
        ProjectDataSourceRegistry registry = new ProjectDataSourceRegistry(project -> {
            factoryCalls.incrementAndGet();
            return created.computeIfAbsent(project.getProjectRef(), key -> new StubDataSource());
        }, 1, 10, 100, projectRef -> {
            if ("a".equals(projectRef) && gateFirstCandidate.compareAndSet(true, false)) {
                candidateSelected.countDown();
                await(borrowerInside);
            }
        });
        registry.execute(project("a"), dataSource -> null);
        ExecutorService executor = executor(2, "registry-lru-linearization-");

        try {
            Future<?> creator = executor.submit(() -> assertThatThrownBy(
                    () -> registry.execute(project("c"), dataSource -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maxPools"));
            await(candidateSelected);
            Future<?> borrower = executor.submit(() -> registry.execute(project("a"), dataSource -> {
                borrowerInside.countDown();
                await(releaseBorrower);
                return null;
            }));
            await(borrowerInside);
            creator.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(created.get("a").closed.get()).isFalse();

            releaseBorrower.countDown();
            borrower.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(created.get("a").closed.get()).isFalse();
            registry.execute(project("a"), dataSource -> null);
            assertThat(factoryCalls.get()).isEqualTo(1);
        }
        finally {
            releaseBorrower.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void blockDuringPoolCreationPreventsPutAndBorrow() throws Exception {
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch factoryProceed = new CountDownLatch(1);
        Map<String, StubDataSource> created = new java.util.concurrent.ConcurrentHashMap<>();
        AtomicInteger factoryCalls = new AtomicInteger();
        AtomicReference<Thread> blockerThread = new AtomicReference<>();
        CountDownLatch blockerStarted = new CountDownLatch(1);
        ProjectDataSourceRegistry registry = new ProjectDataSourceRegistry(project -> {
            factoryCalls.incrementAndGet();
            factoryEntered.countDown();
            await(factoryProceed);
            return created.computeIfAbsent(project.getProjectRef(), key -> new StubDataSource());
        }, 4, 10, 100);
        ExecutorService executor = executor(2, "registry-race-");

        try {
            Future<?> borrower = executor.submit(() -> {
                try {
                    registry.execute(project("a"), dataSource -> null);
                }
                catch (IllegalStateException exception) {
                    assertThat(exception).hasMessageContaining("blocked");
                }
            });
            await(factoryEntered);
            Future<?> blocker = executor.submit(() -> {
                blockerThread.set(Thread.currentThread());
                blockerStarted.countDown();
                registry.blockAndDrain("a");
            });
            await(blockerStarted);
            awaitThreadState(blockerThread.get(), Thread.State.BLOCKED);

            factoryProceed.countDown();
            borrower.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            blocker.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        finally {
            factoryProceed.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(created.get("a").closed.get()).isTrue();
        assertThatThrownBy(() -> registry.execute(project("a"), dataSource -> null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("blocked");
        assertThat(factoryCalls.get()).isEqualTo(1);
    }

    @Test
    void lruEvictedRefCanRebuildOnNextAccess() {
        Map<String, AtomicInteger> created = new java.util.concurrent.ConcurrentHashMap<>();
        ProjectDataSourceRegistry registry = new ProjectDataSourceRegistry(project -> {
            created.computeIfAbsent(project.getProjectRef(), key -> new AtomicInteger()).incrementAndGet();
            return new StubDataSource();
        }, 2, 10, 100);

        registry.execute(project("a"), dataSource -> null);
        registry.execute(project("b"), dataSource -> null);
        registry.execute(project("c"), dataSource -> null);
        registry.execute(project("a"), dataSource -> null);

        assertThat(created.get("a").get()).isEqualTo(2);
    }

}
