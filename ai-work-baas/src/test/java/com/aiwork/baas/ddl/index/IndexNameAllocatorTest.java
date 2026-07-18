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
    void occupiedByForeignIndexFallsBackToStableHashedName() {
        var allocation = IndexNameAllocator.allocate(false, "email", Set.of("idx_email"), null);
        assertThat(allocation.name()).isNotEqualTo("idx_email").startsWith("idx_email_").hasSizeLessThanOrEqualTo(64);
        assertThat(allocation.alreadySatisfied()).isFalse();

        var again = IndexNameAllocator.allocate(false, "email", Set.of("idx_email"), null);
        assertThat(again.name()).isEqualTo(allocation.name());
    }

    @Test
    void hashCollisionAdvancesDeterministically() {
        var first = IndexNameAllocator.allocate(false, "email", Set.of("idx_email"), null);
        var second = IndexNameAllocator.allocate(false, "email", Set.of("idx_email", first.name()), null);
        assertThat(second.name()).isNotEqualTo(first.name()).hasSizeLessThanOrEqualTo(64);
    }

}
