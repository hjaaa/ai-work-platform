/*
 *
 *      Copyright (c) 2018-2026, lengleng All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice,
 *  this list of conditions and the following disclaimer.
 *  Redistributions in binary form must reproduce the above copyright
 *  notice, this list of conditions and the following disclaimer in the
 *  documentation and/or other materials provided with the distribution.
 *  Neither the name of the pig4cloud.com developer nor the names of its
 *  contributors may be used to endorse or promote products derived from
 *  this software without specific prior written permission.
 *  Author: lengleng (wangiegie@gmail.com)
 *
 */

package com.aiwork.baas.ddl.index;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IndexNameAllocatorTest {

    @Test
    void canonicalNamesUsePrefixes() {
        assertThat(IndexNameAllocator.canonicalName(true, "email")).isEqualTo("uk_email");
        assertThat(IndexNameAllocator.canonicalName(false, "email")).isEqualTo("idx_email");
    }

    @Test
    void longColumnNameTruncatedWithStableHashWithin64() {
        String longName = "c".repeat(64);
        String first = IndexNameAllocator.canonicalName(false, longName);
        String second = IndexNameAllocator.canonicalName(false, longName);
        assertThat(first).isEqualTo(second).hasSizeLessThanOrEqualTo(64).startsWith("idx_");
        assertThat(IndexNameAllocator.canonicalName(true, longName)).hasSizeLessThanOrEqualTo(64).startsWith("uk_");
    }

    @Test
    void namesAt60And61ColumnsRespect64CharacterLimit() {
        assertThat(IndexNameAllocator.canonicalName(false, "c".repeat(60))).hasSize(64)
            .isEqualTo("idx_" + "c".repeat(60));
        assertThat(IndexNameAllocator.canonicalName(false, "c".repeat(61))).hasSize(64)
            .startsWith("idx_").containsPattern("_[0-9a-f]{8}$");
    }

    @Test
    void unoccupiedCanonicalNameUsedDirectly() {
        var allocation = IndexNameAllocator.allocate(false, "email", Set.of("idx_other"), null);
        assertThat(allocation.name()).isEqualTo("idx_email");
        assertThat(allocation.alreadySatisfied()).isFalse();
    }

    @Test
    void occupiedByTargetItselfIsIdempotent() {
        var allocation = IndexNameAllocator.allocate(false, "email", Set.of("idx_email"), "idx_email");
        assertThat(allocation.alreadySatisfied()).isTrue();
    }

    @Test
    void idempotentMatchIsCaseInsensitiveAndPreservesActualName() {
        var allocation = IndexNameAllocator.allocate(false, "email", Set.of("IDX_EMAIL"), "IDX_EMAIL");
        assertThat(allocation.name()).isEqualTo("IDX_EMAIL");
        assertThat(allocation.alreadySatisfied()).isTrue();
    }

    @Test
    void occupiedByForeignIndexFallsBackToStableHashedName() {
        var allocation = IndexNameAllocator.allocate(false, "email", Set.of("idx_email"), null);
        assertThat(allocation.name()).isNotEqualTo("idx_email").startsWith("idx_email_").hasSizeLessThanOrEqualTo(64);
        assertThat(allocation.alreadySatisfied()).isFalse();

        var again = IndexNameAllocator.allocate(false, "email", Set.of("idx_email"), null);
        assertThat(again.name()).isEqualTo(allocation.name());
    }

    @Test
    void uppercaseForeignNameOccupiesCanonicalName() {
        var allocation = IndexNameAllocator.allocate(false, "email", Set.of("IDX_EMAIL"), null);
        assertThat(allocation.name()).startsWith("idx_email_").isNotEqualToIgnoringCase("IDX_EMAIL");
        assertThat(allocation.alreadySatisfied()).isFalse();
    }

    @Test
    void hashCollisionAdvancesDeterministically() {
        var first = IndexNameAllocator.allocate(false, "email", Set.of("idx_email"), null);
        var second = IndexNameAllocator.allocate(false, "email", Set.of("idx_email", first.name()), null);
        assertThat(second.name()).isNotEqualTo(first.name()).hasSizeLessThanOrEqualTo(64);
    }

    @Test
    void consecutiveHashedNameConflictsAdvanceWithoutExceeding64Characters() {
        var first = IndexNameAllocator.allocate(false, "c".repeat(61),
                Set.of(IndexNameAllocator.canonicalName(false, "c".repeat(61))), null);
        var second = IndexNameAllocator.allocate(false, "c".repeat(61),
                Set.of(IndexNameAllocator.canonicalName(false, "c".repeat(61)), first.name()), null);
        assertThat(second.name()).isNotEqualTo(first.name()).hasSize(64);
    }

}
