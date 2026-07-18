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

package com.aiwork.baas.provision;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhysicalPreconditionsTest {

    @Test
    void acceptsSixteenKibPage() {
        PhysicalPreconditions preconditions = new PhysicalPreconditions(() -> 16384L);
        assertThatCode(preconditions::assertSatisfied).doesNotThrowAnyException();
        assertThat(preconditions.isSatisfied()).isTrue();
    }

    @Test
    void rejectsOtherPageSizesFailClosed() {
        PhysicalPreconditions preconditions = new PhysicalPreconditions(() -> 8192L);
        assertThatThrownBy(preconditions::assertSatisfied).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("innodb_page_size");
    }

    @Test
    void queryFailureIsFailClosed() {
        PhysicalPreconditions preconditions = new PhysicalPreconditions(() -> {
            throw new RuntimeException("db unreachable");
        });
        assertThatThrownBy(preconditions::assertSatisfied).isInstanceOf(IllegalStateException.class);
    }

}
