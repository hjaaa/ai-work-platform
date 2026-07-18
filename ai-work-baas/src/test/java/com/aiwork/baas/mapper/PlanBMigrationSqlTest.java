package com.aiwork.baas.mapper;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class PlanBMigrationSqlTest {

    @Container
    static MySQLContainer mysql = new MySQLContainer("mysql:8.4").withUsername("root")
        .withPassword("root")
        .withDatabaseName("ai_work_baas");

    static JdbcTemplate jdbc;

    @BeforeAll
    static void migratePlanABaseline() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(), "root", "root");
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE baas_project (id bigint NOT NULL AUTO_INCREMENT, "
                + "runtime_db_password_cipher varchar(512), status varchar(16) NOT NULL, "
                + "PRIMARY KEY (id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE baas_table (id bigint NOT NULL AUTO_INCREMENT, comment varchar(255), "
                + "status varchar(16) NOT NULL DEFAULT 'ACTIVE', PRIMARY KEY (id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE baas_column (id bigint NOT NULL AUTO_INCREMENT, comment varchar(255), "
                + "default_value varchar(255), PRIMARY KEY (id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE baas_ddl_log (id bigint NOT NULL AUTO_INCREMENT, operation_id varchar(64) "
                + "NOT NULL, project_id bigint NOT NULL, ddl_text text NOT NULL, step varchar(32) NOT NULL, "
                + "status varchar(16) NOT NULL, error_msg varchar(1024), retry_count int NOT NULL DEFAULT 0, "
                + "create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (id), "
                + "UNIQUE KEY uk_operation (operation_id)) ENGINE=InnoDB");
        jdbc.update("INSERT INTO baas_ddl_log(operation_id,project_id,ddl_text,step,status) "
                + "VALUES ('legacy-op',1,'ALTER secret','PREPARED','RUNNING')");

        String repositoryRoot = System.getProperty("maven.multiModuleProjectDirectory",
                Path.of("..").toAbsolutePath().normalize().toString());
        Path script = Path.of(repositoryRoot, "db", "ai_work_baas_plan_b_migration.sql");
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(script));
        }
    }

    @Test
    void migrationRunsAndMatchesTerminalColumnContract() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA='ai_work_baas' AND TABLE_NAME='baas_ddl_log' "
                + "AND COLUMN_NAME='update_time'", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COLUMN_TYPE FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA='ai_work_baas' AND TABLE_NAME='baas_ddl_log' AND COLUMN_NAME='table_id'",
                String.class)).isEqualTo("bigint");
        assertThat(jdbc.queryForObject("SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA='ai_work_baas' AND TABLE_NAME='baas_ddl_log' "
                + "AND COLUMN_NAME='operation_type'", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA='ai_work_baas' AND TABLE_NAME='baas_ddl_log' "
                + "AND COLUMN_NAME='request_hash'", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM baas_ddl_log WHERE operation_id='legacy-op'",
                String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_msg FROM baas_ddl_log WHERE operation_id='legacy-op'",
                String.class)).isEqualTo("LEGACY_RUNNING_NOT_RESUMABLE");
        assertThat(jdbc.queryForObject("SELECT ddl_text FROM baas_ddl_log WHERE operation_id='legacy-op'",
                String.class)).isEqualTo("LEGACY_DDL_REDACTED");
    }

}
