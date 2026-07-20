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

package com.aiwork.baas.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * 有界扫描批次：每轮合并最新记录与向旧 ID 推进的 keyset 游标，兼顾新任务及时性与旧任务公平性。
 *
 * @param <T> 扫描记录类型
 * @author ai-work
 * @date 2026/07/20
 */
final class BoundedScanCursor<T> {

    private static final int LATEST_LIMIT = 50;

    private static final int CURSOR_LIMIT = 50;

    private final AtomicLong cursor = new AtomicLong();

    List<T> nextBatch(Function<Integer, List<T>> latestLoader,
            BiFunction<Long, Integer, List<T>> cursorLoader, ToLongFunction<T> idExtractor) {
        List<T> cursorBatch = cursorLoader.apply(cursor.get(), CURSOR_LIMIT);
        if (cursorBatch.isEmpty() && cursor.get() > 0) {
            cursor.set(0);
            cursorBatch = cursorLoader.apply(0L, CURSOR_LIMIT);
        }
        if (!cursorBatch.isEmpty()) {
            cursor.set(idExtractor.applyAsLong(cursorBatch.get(cursorBatch.size() - 1)));
        }

        Map<Long, T> batchById = new LinkedHashMap<>();
        for (T record : latestLoader.apply(LATEST_LIMIT)) {
            batchById.put(idExtractor.applyAsLong(record), record);
        }
        for (T record : cursorBatch) {
            batchById.putIfAbsent(idExtractor.applyAsLong(record), record);
        }
        return List.copyOf(batchById.values());
    }

}
