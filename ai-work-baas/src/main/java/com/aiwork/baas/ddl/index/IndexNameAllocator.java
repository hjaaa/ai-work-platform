package com.aiwork.baas.ddl.index;

import com.aiwork.baas.ddl.RequestFingerprint;

import java.util.Locale;
import java.util.Set;

/**
 * 统一索引名分配器(spec §7.3):ADD、唯一/普通替换、owner 自动补索引、RENAME INDEX 共用。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public final class IndexNameAllocator {

    private static final int MAX_INDEX_NAME_LENGTH = 64;

    private static final int HASH_LENGTH = 8;

    public record Allocation(String name, boolean alreadySatisfied) {
    }

    private IndexNameAllocator() {
    }

    public static String canonicalName(boolean unique, String columnName) {
        String prefix = unique ? "uk_" : "idx_";
        String name = prefix + columnName;
        if (name.length() <= MAX_INDEX_NAME_LENGTH) {
            return name;
        }
        return hashedName(prefix, columnName, columnName);
    }

    /**
     * @param existingIndexNames 锁内读取的全表现有索引名
     * @param currentIndexNameOnColumn 目标列上现有同类索引的实际名(无则 null)
     * @return 确定性分配结果
     */
    public static Allocation allocate(boolean unique, String columnName, Set<String> existingIndexNames,
            String currentIndexNameOnColumn) {
        String canonical = canonicalName(unique, columnName);
        Set<String> normalizedExistingNames = existingIndexNames.stream()
            .map(IndexNameAllocator::normalizeName)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (sameName(canonical, currentIndexNameOnColumn)) {
            return new Allocation(currentIndexNameOnColumn, true);
        }
        if (!normalizedExistingNames.contains(normalizeName(canonical))) {
            return new Allocation(canonical, false);
        }
        String prefix = unique ? "uk_" : "idx_";
        for (int sequence = 0;; sequence++) {
            String salt = sequence == 0 ? columnName : columnName + "#" + sequence;
            String candidate = hashedName(prefix, columnName, salt);
            if (sameName(candidate, currentIndexNameOnColumn)) {
                return new Allocation(currentIndexNameOnColumn, true);
            }
            if (!normalizedExistingNames.contains(normalizeName(candidate))) {
                return new Allocation(candidate, false);
            }
        }
    }

    private static String hashedName(String prefix, String columnName, String salt) {
        String hash = RequestFingerprint.sha256Hex(salt).substring(0, HASH_LENGTH);
        int keep = Math.min(columnName.length(), MAX_INDEX_NAME_LENGTH - prefix.length() - 1 - HASH_LENGTH);
        return prefix + columnName.substring(0, keep) + "_" + hash;
    }

    private static boolean sameName(String first, String second) {
        return second != null && normalizeName(first).equals(normalizeName(second));
    }

    private static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

}
