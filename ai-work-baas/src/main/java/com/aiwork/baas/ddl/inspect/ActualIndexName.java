package com.aiwork.baas.ddl.inspect;

/**
 * 仅承载从 information_schema 读取的实际索引名，用于按物理实际名称定位 DROP/RENAME 操作。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public final class ActualIndexName {

    private static final int MAX_INDEX_NAME_LENGTH = 64;

    private final String value;

    private ActualIndexName(String value) {
        this.value = value;
    }

    public static ActualIndexName fromInformationSchema(String indexName) {
        if (indexName == null || indexName.isBlank()
                || indexName.codePointCount(0, indexName.length()) > MAX_INDEX_NAME_LENGTH
                || indexName.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("illegal information_schema index name");
        }
        return new ActualIndexName(indexName);
    }

    public String quotedForDdl() {
        return "`" + value.replace("`", "``") + "`";
    }

}
