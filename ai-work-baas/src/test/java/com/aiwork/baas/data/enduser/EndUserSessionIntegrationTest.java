package com.aiwork.baas.data.enduser;

import com.aiwork.baas.datasource.ProjectDataSourceRegistry;
import com.aiwork.baas.support.DataPlaneIntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.sql.PreparedStatement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * logout 单会话撤销 / GET user / 改密撤销全部会话 / 软删账户管理裁定(spec §7.2/§7.3/§7.6/§14)。
 * 认证端点携带 end-user Bearer access JWT,须声明 skip-resolve-urls 让平台跳过 Bearer 解析,
 * 否则平台在 ApiKeyAuthFilter 之前拦截返回 424(与 DataRestSecurityIntegrationTest 同源)。
 */
@TestPropertySource(properties = "security.oauth2.client.skip-resolve-urls=/data/**")
class EndUserSessionIntegrationTest extends DataPlaneIntegrationTestSupport {

    @Autowired
    ProjectDataSourceRegistry registry;

    private String authUrl(String path) {
        return "http://localhost:" + port + "/data/" + fixture.project().getProjectRef() + "/auth/v1" + path;
    }

    /** 直接在项目库软删用户,模拟 Studio 侧软删后旧 JWT 仍在 TTL 内的状态。 */
    private void softDeleteUser(long userId) {
        registry.execute(fixture.project(), dataSource -> {
            try (var connection = dataSource.getConnection();
                    PreparedStatement statement = connection.prepareStatement(
                            "UPDATE `_users` SET deleted_at = NOW() WHERE id = ?")) {
                statement.setLong(1, userId);
                statement.executeUpdate();
                return null;
            }
            catch (java.sql.SQLException exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private JsonNode signup(String email, String password) {
        return json(call(HttpMethod.POST, authUrl("/signup"), headers(fixture.publishableKey(), null),
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private JsonNode login(String email, String password) {
        return json(call(HttpMethod.POST, authUrl("/token?grant_type=password"),
                headers(fixture.publishableKey(), null),
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private ResponseEntity<String> refresh(String token) {
        return call(HttpMethod.POST, authUrl("/token?grant_type=refresh_token"),
                headers(fixture.publishableKey(), null), "{\"refresh_token\":\"" + token + "\"}");
    }

    @Test
    void logoutRevokesOnlyCurrentSessionAndIsIdempotent() {
        JsonNode sessionA = signup("multi@example.com", "password-ok");
        JsonNode sessionB = login("multi@example.com", "password-ok");
        String jwtA = sessionA.get("access_token").textValue();

        ResponseEntity<String> logout = call(HttpMethod.POST, authUrl("/logout"),
                headers(fixture.publishableKey(), jwtA), null);
        assertThat(logout.getStatusCode().value()).isEqualTo(204);
        // 会话 A 的 refresh 链失效;会话 B 不受影响(§7.6 单会话撤销)
        assertThat(refresh(sessionA.get("refresh_token").textValue()).getStatusCode().value()).isEqualTo(401);
        assertThat(refresh(sessionB.get("refresh_token").textValue()).getStatusCode().value()).isEqualTo(200);
        // 幂等:会话已撤销仍 204
        assertThat(call(HttpMethod.POST, authUrl("/logout"), headers(fixture.publishableKey(), jwtA), null)
            .getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void logoutWithoutJwtIs401() {
        assertThat(call(HttpMethod.POST, authUrl("/logout"), headers(fixture.publishableKey(), null), null)
            .getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void currentUserReturnsProfile() {
        JsonNode session = signup("me@example.com", "password-ok");
        ResponseEntity<String> response = call(HttpMethod.GET, authUrl("/user"),
                headers(fixture.publishableKey(), session.get("access_token").textValue()), null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode user = json(response);
        assertThat(user.get("email").textValue()).isEqualTo("me@example.com");
        assertThat(user.get("id")).isEqualTo(session.get("user").get("id"));
    }

    @Test
    void changePasswordRevokesAllSessionsIncludingCurrent() {
        JsonNode sessionA = signup("chg@example.com", "old-password");
        JsonNode sessionB = login("chg@example.com", "old-password");
        String jwtA = sessionA.get("access_token").textValue();

        ResponseEntity<String> changed = call(HttpMethod.PUT, authUrl("/user/password"),
                headers(fixture.publishableKey(), jwtA),
                "{\"currentPassword\":\"old-password\",\"newPassword\":\"new-password-1\"}");
        assertThat(changed.getStatusCode().value()).isEqualTo(204);
        // 全部会话撤销(含当前):两条 refresh 链均失效
        assertThat(refresh(sessionA.get("refresh_token").textValue()).getStatusCode().value()).isEqualTo(401);
        assertThat(refresh(sessionB.get("refresh_token").textValue()).getStatusCode().value()).isEqualTo(401);
        // 旧密码不可登录,新密码可登录
        assertThat(call(HttpMethod.POST, authUrl("/token?grant_type=password"),
                headers(fixture.publishableKey(), null),
                "{\"email\":\"chg@example.com\",\"password\":\"old-password\"}").getStatusCode().value())
            .isEqualTo(401);
        assertThat(login("chg@example.com", "new-password-1").get("access_token").isTextual()).isTrue();
    }

    @Test
    void wrongCurrentPasswordIs401AndCounted() {
        JsonNode session = signup("wrongcur@example.com", "old-password");
        String jwt = session.get("access_token").textValue();
        for (int i = 0; i < 5; i++) {
            // 合法长度(≥8 字节)但错误的 currentPassword:走完 bcrypt 比对失败 → 401 且计入限速
            assertThat(call(HttpMethod.PUT, authUrl("/user/password"), headers(fixture.publishableKey(), jwt),
                    "{\"currentPassword\":\"wrongpw-" + i + "\",\"newPassword\":\"new-password-1\"}")
                .getStatusCode().value()).isEqualTo(401);
        }
        // currentPassword 错误计入 §12.2 邮箱维度:第 6 次即使密码正确也 429
        ResponseEntity<String> blocked = call(HttpMethod.PUT, authUrl("/user/password"),
                headers(fixture.publishableKey(), jwt),
                "{\"currentPassword\":\"old-password\",\"newPassword\":\"new-password-1\"}");
        assertThat(blocked.getStatusCode().value()).isEqualTo(429);
        assertThat(blocked.getHeaders().getFirst("Retry-After")).isNotBlank();
    }

    @Test
    void newPasswordByteBounds() {
        JsonNode session = signup("bounds@example.com", "old-password");
        String jwt = session.get("access_token").textValue();
        assertThat(call(HttpMethod.PUT, authUrl("/user/password"), headers(fixture.publishableKey(), jwt),
                "{\"currentPassword\":\"old-password\",\"newPassword\":\"1234567\"}").getStatusCode().value())
            .isEqualTo(400);
    }

    @Test
    void overlongCurrentPasswordIs400() {
        JsonNode session = signup("curlen@example.com", "old-password");
        String jwt = session.get("access_token").textValue();
        // currentPassword 同样受 8–72 字节约束(§7.2):73 字节 → 400,先于 bcrypt/限速拒绝
        String overlong = "a".repeat(73);
        assertThat(call(HttpMethod.PUT, authUrl("/user/password"), headers(fixture.publishableKey(), jwt),
                "{\"currentPassword\":\"" + overlong + "\",\"newPassword\":\"new-password-1\"}")
            .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void softDeletedUserRejectedFromAccountManagement() {
        JsonNode session = signup("softdel@example.com", "old-password");
        String jwt = session.get("access_token").textValue();
        long userId = session.get("user").get("id").asLong();
        softDeleteUser(userId);

        // 软删后旧 JWT 仍在 TTL 内:账户管理端点一律 401(§7.3 裁定),不泄露软删细节
        assertThat(call(HttpMethod.GET, authUrl("/user"), headers(fixture.publishableKey(), jwt), null)
            .getStatusCode().value()).isEqualTo(401);
        assertThat(call(HttpMethod.PUT, authUrl("/user/password"), headers(fixture.publishableKey(), jwt),
                "{\"currentPassword\":\"old-password\",\"newPassword\":\"new-password-1\"}")
            .getStatusCode().value()).isEqualTo(401);
        // logout 仍幂等 204:撤销会话无害,不因软删而改变语义
        assertThat(call(HttpMethod.POST, authUrl("/logout"), headers(fixture.publishableKey(), jwt), null)
            .getStatusCode().value()).isEqualTo(204);
    }

}
