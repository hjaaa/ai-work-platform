package com.aiwork.baas.data.enduser;

import com.aiwork.baas.controller.dto.AclRoleDTO;
import com.aiwork.baas.service.EndUserAdminService;
import com.aiwork.baas.support.DataPlaneIntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan D 全链路(spec §14):signup 即登录 → 凭签发 JWT 走 owner 策略 CRUD →
 * refresh → logout 后 access JWT 存续 → 软删/恢复闭环。
 * 认证端点与数据面均携带 end-user Bearer access JWT,须声明 skip-resolve-urls 让平台跳过 Bearer 解析,
 * 否则平台在 ApiKeyAuthFilter 之前拦截返回 424(与 DataRestSecurityIntegrationTest 同源)。
 */
@TestPropertySource(properties = "security.oauth2.client.skip-resolve-urls=/data/**")
class EndUserAuthFullFlowIntegrationTest extends DataPlaneIntegrationTestSupport {

    @Autowired
    EndUserAdminService adminService;

    private String authUrl(String path) {
        return "http://localhost:" + port + "/data/" + fixture.project().getProjectRef() + "/auth/v1" + path;
    }

    @Test
    void fullLifecycle() {
        // 建带 owner 列的数据表并开 authenticated ACL
        createDataTable("notes", List.of(col("title", "varchar", 64, null), col("owner_id", "bigint", null, null)));
        openAcl("notes", allClosed(), allOpen(), "owner_id");

        // signup 即登录
        ResponseEntity<String> signupResponse = call(HttpMethod.POST, authUrl("/signup"),
                headers(fixture.publishableKey(), null),
                "{\"email\":\"flow@example.com\",\"password\":\"password-ok\"}");
        assertThat(signupResponse.getStatusCode().value()).isEqualTo(200);
        JsonNode session = json(signupResponse);
        String access = session.get("access_token").textValue();
        long userId = session.get("user").get("id").longValue();

        // 凭真实签发的 JWT 走数据面:owner 由服务端强制写入 jwt.sub
        ResponseEntity<String> insert = call(HttpMethod.POST, baseUrl() + "/notes",
                headers(fixture.publishableKey(), access), "{\"title\":\"hello\"}");
        assertThat(insert.getStatusCode().value()).isEqualTo(201);
        JsonNode rows = json(call(HttpMethod.GET, baseUrl() + "/notes",
                headers(fixture.publishableKey(), access), null));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("owner_id").longValue()).isEqualTo(userId);

        // refresh:新 access JWT 继续可用
        JsonNode refreshed = json(call(HttpMethod.POST, authUrl("/token?grant_type=refresh_token"),
                headers(fixture.publishableKey(), null),
                "{\"refresh_token\":\"" + session.get("refresh_token").textValue() + "\"}"));
        String access2 = refreshed.get("access_token").textValue();
        assertThat(call(HttpMethod.GET, baseUrl() + "/notes", headers(fixture.publishableKey(), access2), null)
            .getStatusCode().value()).isEqualTo(200);

        // logout 后:refresh 链失效,但 access JWT 在 TTL 内仍可访问 /rest(spec §7.2/§7.5)
        assertThat(call(HttpMethod.POST, authUrl("/logout"), headers(fixture.publishableKey(), access2), null)
            .getStatusCode().value()).isEqualTo(204);
        assertThat(call(HttpMethod.POST, authUrl("/token?grant_type=refresh_token"),
                headers(fixture.publishableKey(), null),
                "{\"refresh_token\":\"" + refreshed.get("refresh_token").textValue() + "\"}")
            .getStatusCode().value()).isEqualTo(401);
        assertThat(call(HttpMethod.GET, baseUrl() + "/notes", headers(fixture.publishableKey(), access2), null)
            .getStatusCode().value()).isEqualTo(200);

        // 软删:login 拒绝,存量 access JWT 仍可访问 /rest(数据面不回查 _users)
        adminService.softDelete(fixture.project(), userId, 1L);
        assertThat(call(HttpMethod.POST, authUrl("/token?grant_type=password"),
                headers(fixture.publishableKey(), null),
                "{\"email\":\"flow@example.com\",\"password\":\"password-ok\"}").getStatusCode().value())
            .isEqualTo(401);
        assertThat(call(HttpMethod.GET, baseUrl() + "/notes", headers(fixture.publishableKey(), access2), null)
            .getStatusCode().value()).isEqualTo(200);
        // 但账户管理端点回查软删状态:同一存量 JWT 访问 GET /user → 401(§7.3 裁定)
        assertThat(call(HttpMethod.GET, authUrl("/user"), headers(fixture.publishableKey(), access2), null)
            .getStatusCode().value()).isEqualTo(401);

        // 恢复:可重新登录
        adminService.restore(fixture.project(), userId, 1L);
        assertThat(call(HttpMethod.POST, authUrl("/token?grant_type=password"),
                headers(fixture.publishableKey(), null),
                "{\"email\":\"flow@example.com\",\"password\":\"password-ok\"}").getStatusCode().value())
            .isEqualTo(200);
    }

}
