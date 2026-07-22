package com.aiwork.baas.data.auth;

import com.aiwork.baas.support.DataPlaneIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * auth 路径的 key 类型规则(spec §7.6):secret → 403;CORS 预检放行 PUT(§12.2)。
 *
 * <p>
 * {@code /data/{ref}/auth/v1/**} 尚无 Spring MVC controller 承载(后续任务落地),不像
 * {@code /rest/v1/{table}} 那样通过 {@code @Inner} 注解自动进入平台 {@code ignore-urls}
 * (见 {@code DataRestController}、{@code PermitAllUrlProperties});为避免平台资源服务器
 * 在数据面自身鉴权(ApiKeyAuthFilter)之前拦截返回 424,测试内显式声明 ignore-urls,
 * 与 {@code DataRestSecurityIntegrationTest} 的 skip-resolve-urls 用法同源。
 */
@TestPropertySource(properties = "security.oauth2.client.ignore-urls=/data/**")
class AuthPathSecretKeyIntegrationTest extends DataPlaneIntegrationTestSupport {

    private String authUrl(String path) {
        return "http://localhost:" + port + "/data/" + fixture.project().getProjectRef() + "/auth/v1" + path;
    }

    @Test
    void secretKeyOnAuthPathIs403() {
        ResponseEntity<String> response = call(HttpMethod.POST, authUrl("/logout"),
                headers(fixture.secretKey(), null), null);
        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void corsPreflightAllowsPut() {
        org.springframework.http.HttpHeaders preflight = new org.springframework.http.HttpHeaders();
        preflight.setOrigin("https://app.example.com");
        preflight.set("Access-Control-Request-Method", "PUT");
        ResponseEntity<String> response = call(HttpMethod.OPTIONS, authUrl("/user/password"), preflight, null);
        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Methods")).contains("PUT");
    }

}
