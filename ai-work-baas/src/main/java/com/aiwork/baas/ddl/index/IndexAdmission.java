package com.aiwork.baas.ddl.index;

import com.aiwork.baas.ddl.type.ColumnType;
import com.aiwork.baas.ddl.type.LogicalColumn;
import com.aiwork.baas.exception.BaasBadRequestException;

import java.util.List;

/**
 * 索引准入矩阵(spec §13):text/json 禁索引;varchar 键长 length×4 ≤ 3072(即 ≤768);
 * 最终二级索引总数与总 key 数分别 ≤ 64。任一不过 400、不记日志、不执行 DDL。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public final class IndexAdmission {

    public static final int MAX_SECONDARY_INDEXES = 64;

    public static final int MAX_TOTAL_INDEXES = 64;

    public static final int MAX_VARCHAR_INDEX_LENGTH = 768;

    private IndexAdmission() {
    }

    /** 纯 DTO 静态预检(锁外可先行,不得作为执行依据)。 */
    public static void validateColumnIndexRequest(ColumnType type, Integer length, boolean unique,
            boolean indexed) {
        if (!unique && !indexed) {
            return;
        }
        if (!type.indexable()) {
            throw new BaasBadRequestException(type.code() + " 列不支持索引(spec §13)");
        }
        if (type == ColumnType.VARCHAR && length != null && length > MAX_VARCHAR_INDEX_LENGTH) {
            throw new BaasBadRequestException(
                    "varchar 索引键长超限:length×4 须 ≤ 3072 字节,即 length ≤ " + MAX_VARCHAR_INDEX_LENGTH);
        }
    }

    /** 锁内按修改后的最终结构复核(spec §9.2:依赖现状的校验不得沿用锁外快照)。 */
    public static void validateFinalStructure(List<LogicalColumn> finalColumns, int finalSecondaryIndexCount) {
        for (LogicalColumn column : finalColumns) {
            validateColumnIndexRequest(column.type(), column.length(), column.unique(), column.indexed());
        }
        if (finalSecondaryIndexCount > MAX_SECONDARY_INDEXES) {
            throw new BaasBadRequestException("二级索引总数超过 InnoDB 上限 " + MAX_SECONDARY_INDEXES);
        }
    }

    /** MySQL 服务层把 PRIMARY 计入 key 总数；CREATE/ALTER/ACL owner 补索引共用此校验。 */
    public static void validateTotalIndexCount(int finalTotalIndexCount) {
        if (finalTotalIndexCount > MAX_TOTAL_INDEXES) {
            throw new BaasBadRequestException("索引总数超过 MySQL 上限 " + MAX_TOTAL_INDEXES);
        }
    }

}
