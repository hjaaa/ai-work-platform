package com.aiwork.baas.data.enduser;

import com.aiwork.baas.datasource.ProjectDataSourceRegistry;
import com.aiwork.baas.support.DataPlaneIntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 清理任务不变量(spec §7.6/§14):已消费未过期行保留、清理后超窗重放仍撤销会话、
 * 过期行删除、grace 内密文不误删。
 */
class RefreshTokenCleanupIntegrationTest extends DataPlaneIntegrationTestSupport {

    @Autowired
    RefreshTokenCleanupJob cleanupJob;

    @Autowired
    ProjectDataSourceRegistry registry;

    private String authUrl(String path) {
        return "http://localhost:" + port + "/data/" + fixture.project().getProjectRef() + "/auth/v1" + path;
    }

    private JsonNode signup(String email) {
        return json(call(HttpMethod.POST, authUrl("/signup"), headers(fixture.publishableKey(), null),
                "{\"email\":\"" + email + "\",\"password\":\"password-ok\"}"));
    }

    private ResponseEntity<String> refresh(String token) {
        return call(HttpMethod.POST, authUrl("/token?grant_type=refresh_token"),
                headers(fixture.publishableKey(), null), "{\"refresh_token\":\"" + token + "\"}");
    }

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

    /** 核心回归(spec v33 P0):清理运行后,超窗重放旧 token 仍判泄露撤销整个会话。 */
    @Test
    void cleanupPreservesReuseDetection() {
        String first = signup("keep@example.com").get("refresh_token").textValue();
        String second = json(refresh(first)).get("refresh_token").textValue();
        // grace 过期 + 跑清理
        update("UPDATE `_refresh_tokens` SET reuse_grace_until = DATE_SUB(NOW(), INTERVAL 60 SECOND) "
                + "WHERE consumed_at IS NOT NULL");
        cleanupJob.cleanupOnce();
        // 已消费未过期的行仍在(仅密文被清)
        assertThat(queryLong("SELECT COUNT(*) FROM `_refresh_tokens` WHERE consumed_at IS NOT NULL"))
            .isEqualTo(1L);
        assertThat(queryLong("SELECT COUNT(*) FROM `_refresh_tokens` "
                + "WHERE consumed_at IS NOT NULL AND replay_payload_ciphertext IS NOT NULL")).isEqualTo(0L);
        // 超窗重放 → 分支③:401 且整个会话撤销(链上新 token 也失效)
        assertThat(refresh(first).getStatusCode().value()).isEqualTo(401);
        assertThat(refresh(second).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void expiredRowsAreDeletedGraceWindowCiphertextKept() {
        String first = signup("mix@example.com").get("refresh_token").textValue();
        refresh(first);
        // 把父 token(已消费)标成已过期;子 token 仍在有效期内
        update("UPDATE `_refresh_tokens` SET expire_time = DATE_SUB(NOW(), INTERVAL 1 SECOND) "
                + "WHERE consumed_at IS NOT NULL");
        long before = queryLong("SELECT COUNT(*) FROM `_refresh_tokens`");
        cleanupJob.cleanupOnce();
        assertThat(queryLong("SELECT COUNT(*) FROM `_refresh_tokens`")).isEqualTo(before - 1);
        // grace 窗口内的未超窗密文不受影响:另建一条会话立即轮换,清理后密文仍在
        String fresh = signup("mix2@example.com").get("refresh_token").textValue();
        refresh(fresh);
        cleanupJob.cleanupOnce();
        assertThat(queryLong("SELECT COUNT(*) FROM `_refresh_tokens` "
                + "WHERE replay_payload_ciphertext IS NOT NULL")).isEqualTo(1L);
    }

    /** §7.6/§13:DELETE 撞行锁时 5 秒 queryTimeout 生效,清理任务不无限阻塞且事务整体回滚。 */
    @Test
    void cleanupTimesOutOnRowLockAndRollsBack() throws Exception {
        signup("locked@example.com");
        update("UPDATE `_refresh_tokens` SET expire_time = DATE_SUB(NOW(), INTERVAL 1 SECOND)");
        long expiredBefore = queryLong("SELECT COUNT(*) FROM `_refresh_tokens` WHERE expire_time < NOW()");
        assertThat(expiredBefore).isGreaterThanOrEqualTo(1L);

        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread locker = new Thread(() -> registry.execute(fixture.project(), dataSource -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement();
                        ResultSet ignored = statement.executeQuery(
                                "SELECT id FROM `_refresh_tokens` WHERE expire_time < NOW() FOR UPDATE")) {
                    locked.countDown();
                    release.await(20, java.util.concurrent.TimeUnit.SECONDS);
                }
                connection.rollback();
                return null;
            }
            catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }));
        locker.setDaemon(true);
        locker.start();
        assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        long startedAt = System.nanoTime();
        cleanupJob.cleanupOnce(); // DELETE 撞行锁 → 5 秒后被驱动 KILL → SQLException → 回滚并跳过该项目
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        release.countDown();

        // 未无限等待(远小于锁持有 20 秒),且因超时回滚,过期行仍在(下轮解锁后再清)
        assertThat(elapsedMillis).isLessThan(15_000L);
        assertThat(queryLong("SELECT COUNT(*) FROM `_refresh_tokens` WHERE expire_time < NOW()"))
            .isEqualTo(expiredBefore);
    }

}
