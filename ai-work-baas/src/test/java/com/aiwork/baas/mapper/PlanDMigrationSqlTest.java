package com.aiwork.baas.mapper;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan D 平台库迁移脚本测试(spec §14:持久化旧平台库升级,不得只测全新初始化)。
 * 参数化覆盖两个真实目标库:Cloud=ai_work_baas、Boot 并库=ai_work——迁移脚本不含 USE、以 DATABASE()
 * 定位,同一脚本对两库真实执行两次(幂等),各自断言列结构与存量回填。
 * 说明:迁移后「v3 Mapper 读取版本列 + 后台扫描 scanOnce() 对持久化存量项目补写版本」由 Task 3 的
 * SystemTableVersionWriteTest(@SpringBootTest,路径②/③)覆盖——那里才有 MyBatis 上下文与项目库;
 * 本裸 JDBC 测试只钉死平台库 DDL 迁移本身。
 */
@Testcontainers
class PlanDMigrationSqlTest {

    private static final List<String> TARGET_DATABASES = List.of("ai_work_baas", "ai_work");

    @Container
    static MySQLContainer mysql = new MySQLContainer("mysql:8.4").withUsername("root")
        .withPassword("root")
        .withDatabaseName("ai_work_baas");

    @BeforeAll
    static void migratePlanCBaselineOnBothTargets() throws Exception {
        // Boot 并库目标 ai_work 在容器内另建;两库分别装载 Plan C 存量后对同一脚本执行两次
        jdbcFor("ai_work_baas").execute("CREATE DATABASE IF NOT EXISTS `ai_work`");
        for (String db : TARGET_DATABASES) {
            seedPlanCBaselineAndMigrate(db);
        }
    }

    private static void seedPlanCBaselineAndMigrate(String db) throws Exception {
        JdbcTemplate jdbc = jdbcFor(db);
        // Plan C 时代 baas_project(已有 ddl_fence_epoch,无 system_table_version)
        jdbc.execute("CREATE TABLE baas_project (id bigint unsigned NOT NULL AUTO_INCREMENT, "
                + "project_ref varchar(20) NOT NULL, name varchar(64) NOT NULL, db_name varchar(64) NOT NULL, "
                + "status varchar(16) NOT NULL, provision_step varchar(32), owner_user_id bigint unsigned NOT NULL, "
                + "allowed_origins json, runtime_db_user varchar(32), runtime_db_password_cipher varchar(512), "
                + "ddl_fence_epoch bigint unsigned NOT NULL DEFAULT 0, "
                + "delete_after datetime, create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                + "PRIMARY KEY (id), UNIQUE KEY uk_project_ref(project_ref), KEY idx_owner(owner_user_id)) "
                + "ENGINE=InnoDB");
        jdbc.update("INSERT INTO baas_project(project_ref,name,db_name,status,owner_user_id) "
                + "VALUES ('legacyref0001','legacy','baas_legacyref0001','ACTIVE',1)");

        String repositoryRoot = System.getProperty("maven.multiModuleProjectDirectory",
                Path.of("..").toAbsolutePath().normalize().toString());
        Path script = Path.of(repositoryRoot, "db", "ai_work_baas_plan_d_migration.sql");
        // 同一脚本(以 DATABASE() 定位)对当前目标库执行两次,验证幂等
        executeMysqlScript(dataSourceFor(db), script);
        executeMysqlScript(dataSourceFor(db), script);
    }

    private static DataSource dataSourceFor(String db) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                mysql.getJdbcUrl().replace("/ai_work_baas", "/" + db), "root", "root");
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        return dataSource;
    }

    private static JdbcTemplate jdbcFor(String db) {
        return new JdbcTemplate(dataSourceFor(db));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ai_work_baas", "ai_work"})
    void addsVersionColumnWithZeroBackfillOnBothTargets(String db) {
        JdbcTemplate jdbc = jdbcFor(db);
        assertThat(jdbc.queryForObject("SELECT COLUMN_TYPE FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA=? AND TABLE_NAME='baas_project' "
                + "AND COLUMN_NAME='system_table_version'", String.class, db)).isEqualTo("int");
        assertThat(jdbc.queryForObject("SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA=? AND TABLE_NAME='baas_project' "
                + "AND COLUMN_NAME='system_table_version'", String.class, db)).isEqualTo("0");
        assertThat(jdbc.queryForObject("SELECT IS_NULLABLE FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA=? AND TABLE_NAME='baas_project' "
                + "AND COLUMN_NAME='system_table_version'", String.class, db)).isEqualTo("NO");
        // 存量行按默认值回填 0(两个目标库均如此)
        assertThat(jdbc.queryForObject("SELECT system_table_version FROM baas_project "
                + "WHERE project_ref='legacyref0001'", Integer.class)).isEqualTo(0);
    }

    @Test
    void freshSeedAlreadyContainsColumn() throws Exception {
        String repositoryRoot = System.getProperty("maven.multiModuleProjectDirectory",
                Path.of("..").toAbsolutePath().normalize().toString());
        String cloudSeed = Files.readString(Path.of(repositoryRoot, "db", "ai_work_baas.sql"));
        String bootSeed = Files.readString(Path.of(repositoryRoot, "db", "ai_work.sql"));
        String initSeed = Files.readString(Path.of(repositoryRoot, "ai-work-baas", "src", "test",
                "resources", "init-metadata.sql"));
        assertThat(cloudSeed).contains("`system_table_version` int NOT NULL DEFAULT 0");
        assertThat(bootSeed).contains("`system_table_version` int NOT NULL DEFAULT 0");
        assertThat(initSeed).contains("`system_table_version` int NOT NULL DEFAULT 0");
    }

    private static void executeMysqlScript(DataSource dataSource, Path script) throws Exception {
        String delimiter = ";";
        StringBuilder statement = new StringBuilder();
        try (var connection = dataSource.getConnection()) {
            for (String line : Files.readAllLines(script)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("DELIMITER ")) {
                    delimiter = trimmed.substring("DELIMITER ".length());
                    continue;
                }
                statement.append(line).append('\n');
                if (trimmed.endsWith(delimiter)) {
                    int end = statement.lastIndexOf(delimiter);
                    String sql = statement.substring(0, end).trim();
                    statement.setLength(0);
                    if (!sql.isEmpty()) {
                        try (var jdbcStatement = connection.createStatement()) {
                            jdbcStatement.execute(sql);
                        }
                    }
                }
            }
        }
    }

}
