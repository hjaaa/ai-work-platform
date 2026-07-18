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

package com.aiwork.baas.ddl;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 操作指纹测试。
 *
 * @author ai-work
 * @date 2026/07/18
 */
class RequestFingerprintTest {

    @Test
    void httpFingerprintIsDeterministicAndSensitiveToEachPart() {
        String base = RequestFingerprint.http("POST", "/studio/projects/ref1/tables", "create", "{\"a\":1}");
        assertThat(base).hasSize(64)
            .isEqualTo(RequestFingerprint.http("POST", "/studio/projects/ref1/tables", "create", "{\"a\":1}"));
        assertThat(RequestFingerprint.http("PATCH", "/studio/projects/ref1/tables", "create", "{\"a\":1}"))
            .isNotEqualTo(base);
        assertThat(RequestFingerprint.http("POST", "/studio/projects/ref1/tables/t2", "create", "{\"a\":1}"))
            .isNotEqualTo(base);
        assertThat(RequestFingerprint.http("POST", "/studio/projects/ref1/tables", "alter", "{\"a\":1}"))
            .isNotEqualTo(base);
        assertThat(RequestFingerprint.http("POST", "/studio/projects/ref1/tables", "create", "{\"a\":2}"))
            .isNotEqualTo(base);
    }

    @Test
    void deleteWithEmptyBodyStillDistinguishesTablesByPath() {
        String tableA = RequestFingerprint.http("DELETE", "/studio/projects/ref1/tables/orders", "drop", "");
        String tableB = RequestFingerprint.http("DELETE", "/studio/projects/ref1/tables/users", "drop", "");
        assertThat(tableA).isNotEqualTo(tableB);
    }

    @Test
    void canonicalBodyIsKeyOrderInsensitiveAndDropsNulls() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("b", 2);
        first.put("a", 1);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("a", 1);
        second.put("b", 2);
        second.put("c", null);
        assertThat(RequestFingerprint.canonicalBody(first)).isEqualTo(RequestFingerprint.canonicalBody(second));
        assertThat(RequestFingerprint.canonicalBody(null)).isEmpty();
    }

    @Test
    void internalPayloadsFollowVersionedLineFormat() {
        String cleanup = RequestFingerprint.cleanupDrop(7L, 42L, LocalDateTime.of(2026, 7, 25, 12, 34, 56));
        assertThat(cleanup).hasSize(64)
            .isEqualTo(RequestFingerprint.cleanupDrop(7L, 42L, LocalDateTime.of(2026, 7, 25, 12, 34, 56, 999)));
        assertThat(RequestFingerprint.cleanupDrop(7L, 43L, LocalDateTime.of(2026, 7, 25, 12, 34, 56)))
            .isNotEqualTo(cleanup);

        String reconcile = RequestFingerprint.scheduledReconcile(7L, "op-1");
        assertThat(reconcile).isNotEqualTo(RequestFingerprint.scheduledReconcile(7L, "op-2"));
    }

}
