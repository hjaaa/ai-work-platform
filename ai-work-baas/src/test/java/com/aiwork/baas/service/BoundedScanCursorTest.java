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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 有界扫描最新配额与回绕 keyset 游标测试。
 *
 * @author ai-work
 * @date 2026/07/20
 */
class BoundedScanCursorTest {

    @Test
    void targetBehindMoreThanOneHundredBlockingRecordsIsEventuallyProcessedAndLatestFailureIsRetried() {
        List<Record> records = new ArrayList<>();
        for (long id = 1; id <= 152; id++) {
            records.add(new Record(id));
        }
        records.sort(Comparator.comparingLong(Record::id).reversed());
        BoundedScanCursor<Record> cursor = new BoundedScanCursor<>();
        List<List<Record>> rounds = new ArrayList<>();

        for (int round = 0; round < 4; round++) {
            rounds.add(cursor.nextBatch(limit -> latest(records, limit),
                    (beforeId, limit) -> before(records, beforeId, limit), Record::id));
        }

        assertThat(rounds).allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(100));
        assertThat(rounds).allSatisfy(batch -> assertThat(batch).extracting(Record::id).contains(152L));
        assertThat(rounds.subList(0, 3)).allSatisfy(batch -> assertThat(batch).extracting(Record::id)
            .doesNotContain(1L));
        assertThat(rounds.get(3)).extracting(Record::id).contains(1L);

        List<Record> wrapped = cursor.nextBatch(limit -> latest(records, limit),
                (beforeId, limit) -> before(records, beforeId, limit), Record::id);
        assertThat(wrapped).extracting(Record::id).contains(152L);
    }

    private static List<Record> latest(List<Record> records, int limit) {
        return records.stream().limit(limit).toList();
    }

    private static List<Record> before(List<Record> records, long beforeId, int limit) {
        return records.stream().filter(record -> beforeId == 0 || record.id() < beforeId).limit(limit).toList();
    }

    private record Record(long id) {
    }

}
