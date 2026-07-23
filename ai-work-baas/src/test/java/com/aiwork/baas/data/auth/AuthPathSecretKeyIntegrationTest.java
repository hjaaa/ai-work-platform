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
    void secretKeyOnSignupIs403() {
        ResponseEntity<String> response = call(HttpMethod.POST, authUrl("/signup"),
                headers(fixture.secretKey(), null), "{\"email\":\"x@example.com\",\"password\":\"password-ok\"}");
        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    /**
     * 编码路径绕过面(§7.6):getRequestURI() 保留 %61uth 原文,Spring 路由解码为 auth 命中 signup;
     * 过滤器基于原始 URI 的 isAuthPath 判定会漏判,controller 层兜底须仍返回 403 而非放行创建用户。
     */
    @Test
    void secretKeyOnPercentEncodedAuthPathIs403() {
        String encoded = "http://localhost:" + port + "/data/" + fixture.project().getProjectRef()
                + "/%61uth/v1/signup";
        ResponseEntity<String> response = call(HttpMethod.POST, encoded, headers(fixture.secretKey(), null),
                "{\"email\":\"x@example.com\",\"password\":\"password-ok\"}");
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
