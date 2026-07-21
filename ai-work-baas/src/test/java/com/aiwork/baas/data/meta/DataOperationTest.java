package com.aiwork.baas.data.meta;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据操作 ACL 标记单测。
 *
 * @author ai-work
 * @date 2026/07/21
 */
class DataOperationTest {

    @Test
    void aclLabelDoesNotDependOnDefaultLocale() {
        Locale originalLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(DataOperation.INSERT.aclLabel()).isEqualTo("insert");
        } finally {
            Locale.setDefault(originalLocale);
        }
    }

}
