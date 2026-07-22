package com.aiwork.baas.db;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * boot 并库脚本一致性(spec §5):ai_work.sql 与 ai_work_baas.sql 的 baas 表 DDL 逐语句一致,防漂移。
 *
 * @author ai-work
 * @date 2026/07/21
 */
class BaasSqlSeedConsistencyTest {

    private static final Pattern CREATE_TABLE = Pattern.compile("CREATE TABLE `baas_[^;]+;", Pattern.DOTALL);

    private static List<String> baasStatements(Path file) throws Exception {
        String content = Files.readString(file);
        Matcher matcher = CREATE_TABLE.matcher(content);
        List<String> statements = new java.util.ArrayList<>();
        while (matcher.find()) {
            statements.add(matcher.group().replaceAll("\\s+", " ").trim());
        }
        return statements;
    }

    @Test
    void baasTableDdlIdenticalAcrossSeedFiles() throws Exception {
        // 测试工作目录为 ai-work-baas 模块目录
        List<String> canonical = baasStatements(Path.of("..", "db", "ai_work_baas.sql"));
        List<String> mirrored = baasStatements(Path.of("..", "db", "ai_work.sql"));

        assertThat(canonical).hasSize(8);
        assertThat(mirrored).containsExactlyElementsOf(canonical);
    }

}
