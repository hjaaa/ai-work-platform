package com.aiwork.baas.data.enduser;

import com.aiwork.baas.datasource.ProjectDataSourceRegistry;
import com.aiwork.baas.support.DataPlaneIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 清理任务分批推进(spec §7.6/§13):批大小设为 2,插入 5 条过期 token,单次 cleanupOnce 须跨多批
 * 全部清空(而非只清一批),验证分批循环正确 drain 至空。
 */
@TestPropertySource(properties = "baas.auth.cleanup-batch-size=2")
class RefreshTokenCleanupBatchingTest extends DataPlaneIntegrationTestSupport {

    @Autowired
    RefreshTokenCleanupJob cleanupJob;

    @Autowired
    ProjectDataSourceRegistry registry;

    private int update(String sql) {
        return registry.execute(fixture.project(), dataSource -> {
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                return statement.executeUpdate(sql);
            }
            catch (SQLException exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private long queryLong(String sql) {
        return registry.execute(fixture.project(), dataSource -> {
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery(sql)) {
                resultSet.next();
                return resultSet.getLong(1);
            }
            catch (SQLException exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    @Test
    void cleanupDrainsAllExpiredRowsAcrossMultipleBatches() {
        // 5 条过期 token(token_hash 唯一,用 SHA2(UUID()) 保证不撞 uk_token_hash)
        assertThat(update("INSERT INTO `_refresh_tokens` (session_id, token_hash, expire_time) VALUES "
                + "(1, SHA2(UUID(),256), DATE_SUB(NOW(), INTERVAL 1 DAY)), "
                + "(1, SHA2(UUID(),256), DATE_SUB(NOW(), INTERVAL 1 DAY)), "
                + "(1, SHA2(UUID(),256), DATE_SUB(NOW(), INTERVAL 1 DAY)), "
                + "(1, SHA2(UUID(),256), DATE_SUB(NOW(), INTERVAL 1 DAY)), "
                + "(1, SHA2(UUID(),256), DATE_SUB(NOW(), INTERVAL 1 DAY))")).isEqualTo(5);
        assertThat(queryLong("SELECT COUNT(*) FROM `_refresh_tokens` WHERE expire_time < NOW()")).isEqualTo(5L);

        // 批大小=2 → 需 3 批(2+2+1)全部删除,验证分批循环 drain 至空
        cleanupJob.cleanupOnce();

        assertThat(queryLong("SELECT COUNT(*) FROM `_refresh_tokens` WHERE expire_time < NOW()")).isEqualTo(0L);
    }
}
