package com.aiwork.baas.data.enduser;

import com.aiwork.baas.datasource.ProjectDataSourceRegistry;
import com.aiwork.baas.support.DataPlaneIntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * refresh 四分支(spec §7.6/§14):轮换、grace 幂等重放、超窗撤销会话、401 兜底、并发、不限速。
 */
class EndUserRefreshIntegrationTest extends DataPlaneIntegrationTestSupport {

    @Autowired
    ProjectDataSourceRegistry registry;

    private String authUrl(String path) {
        return "http://localhost:" + port + "/data/" + fixture.project().getProjectRef() + "/auth/v1" + path;
    }

    private JsonNode signup(String email) {
        return json(call(HttpMethod.POST, authUrl("/signup"), headers(fixture.publishableKey(), null),
                "{\"email\":\"" + email + "\",\"password\":\"password-ok\"}"));
    }

    private ResponseEntity<String> refresh(String refreshToken) {
        return call(HttpMethod.POST, authUrl("/token?grant_type=refresh_token"),
                headers(fixture.publishableKey(), null), "{\"refresh_token\":\"" + refreshToken + "\"}");
    }

    /** 分支①:正常轮换——新旧 refresh token 不同,新 token 可继续使用,旧 token 进入 grace。 */
    @Test
    void rotationIssuesNewToken() {
        String first = signup("rot@example.com").get("refresh_token").textValue();
        ResponseEntity<String> rotated = refresh(first);
        assertThat(rotated.getStatusCode().value()).isEqualTo(200);
        String second = json(rotated).get("refresh_token").textValue();
        assertThat(second).isNotEqualTo(first);
        assertThat(refresh(second).getStatusCode().value()).isEqualTo(200);
    }

    /** 分支②:grace 内重放旧 token 返回逐字相同的响应(幂等)。 */
    @Test
    void graceReplayReturnsIdenticalResponse() {
        String first = signup("grace@example.com").get("refresh_token").textValue();
        String rotatedBody = refresh(first).getBody();
        String replayedBody = refresh(first).getBody();
        assertThat(replayedBody).isEqualTo(rotatedBody);
    }

    /** 分支③:超窗重放判定泄露,整个会话撤销——链上最新 token 也随之失效。 */
    @Test
    void reuseAfterGraceRevokesWholeSession() throws Exception {
        String first = signup("leak@example.com").get("refresh_token").textValue();
        String second = json(refresh(first)).get("refresh_token").textValue();
        // 直接把 grace 截止时间改到过去,免等待 10 秒
        expireGrace("leak@example.com");
        assertThat(refresh(first).getStatusCode().value()).isEqualTo(401);
        // 会话已 REVOKED:链上最新 token 也 401(分支④)
        assertThat(refresh(second).getStatusCode().value()).isEqualTo(401);
    }

    /** 分支④:不存在/会话 REVOKED → 401。 */
    @Test
    void unknownTokenIs401() {
        assertThat(refresh("rt_does-not-exist").getStatusCode().value()).isEqualTo(401);
    }

    /** 并发 refresh:同一旧 token 两个并发请求,一个轮换一个 grace 重放,响应体一致。 */
    @Test
    void concurrentRefreshIsIdempotent() throws Exception {
        String first = signup("conc@example.com").get("refresh_token").textValue();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<String> one = pool.submit(() -> {
                start.await();
                return refresh(first).getBody();
            });
            Future<String> two = pool.submit(() -> {
                start.await();
                return refresh(first).getBody();
            });
            start.countDown();
            assertThat(one.get()).isEqualTo(two.get());
        }
        finally {
            pool.shutdownNow();
        }
    }

    /** refresh/logout 不限速(spec §12.2):连续 40 次轮换全部放行。 */
    @Test
    void refreshIsNotRateLimited() {
        String token = signup("norl@example.com").get("refresh_token").textValue();
        for (int i = 0; i < 40; i++) {
            org.springframework.http.ResponseEntity<String> response = refresh(token);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            token = json(response).get("refresh_token").textValue();
        }
    }

    /** 直接对项目库把 reuse_grace_until 改为过去(免等 10 秒 grace)。 */
    private void expireGrace(String email) {
        registry.execute(fixture.project(), dataSource -> {
            try (java.sql.Connection connection = dataSource.getConnection();
                    java.sql.Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE `_refresh_tokens` SET "
                        + "reuse_grace_until = DATE_SUB(NOW(), INTERVAL 60 SECOND) "
                        + "WHERE consumed_at IS NOT NULL");
                return null;
            }
            catch (java.sql.SQLException exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

}
