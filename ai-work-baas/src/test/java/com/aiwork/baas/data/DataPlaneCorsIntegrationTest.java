package com.aiwork.baas.data;

import com.aiwork.baas.data.auth.ApiKeyAuthFilter;
import com.aiwork.baas.data.context.DataRequestContext;
import com.aiwork.baas.data.cors.DataPlaneCorsFilter;
import com.aiwork.baas.datasource.ProjectDataSourceRegistry;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.mapper.BaasProjectMapper;
import com.aiwork.baas.service.ProjectKeyService;
import com.aiwork.baas.support.DataPlaneIntegrationTestSupport;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CORS 完整契约(spec §12.2:方法/请求头/Expose-Headers/Vary/预检不触碰项目库)。
 *
 * @author ai-work
 * @date 2026/07/21
 */
class DataPlaneCorsIntegrationTest extends DataPlaneIntegrationTestSupport {

    @Autowired
    private ProjectKeyService keyService;

    @Autowired
    private BaasProjectMapper projectMapper;

    @Autowired
    private ProjectDataSourceRegistry registry;

    @Autowired
    @Qualifier("dataPlaneCorsFilterRegistration")
    private FilterRegistrationBean<DataPlaneCorsFilter> corsRegistration;

    @Autowired
    @Qualifier("apiKeyAuthFilterRegistration")
    private FilterRegistrationBean<ApiKeyAuthFilter> apiKeyRegistration;

    @BeforeEach
    void setUpTable() {
        createDataTable("corsy", List.of(col("v", "int", null, null)));
    }

    @AfterEach
    void closeProjectPool() {
        registry.blockAndDrain(fixture.project().getProjectRef());
    }

    private HttpHeaders preflight(String origin, String method) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Origin", origin);
        headers.set("Access-Control-Request-Method", method);
        headers.set("Access-Control-Request-Headers", "apikey, content-type, prefer");
        return headers;
    }

    @Test
    void patchPreflightAllowsMethodsAndHeaders() {
        ResponseEntity<String> response = call(HttpMethod.OPTIONS, baseUrl() + "/corsy",
                preflight("https://app.example.com", "PATCH"), null);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Origin")).isEqualTo("*");
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Methods"))
            .isEqualTo("GET, POST, PATCH, DELETE, OPTIONS");
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Headers"))
            .isEqualTo("apikey, Authorization, Content-Type, Prefer");
        assertThat(response.getHeaders().getFirst("Access-Control-Max-Age")).isEqualTo("3600");
        assertThat(response.getHeaders().get("Vary")).contains("Origin", "Access-Control-Request-Method",
                "Access-Control-Request-Headers");
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Credentials")).isNull();
    }

    @Test
    void optionsWithoutCompletePreflightHeadersCannotBypassApiKey() {
        HttpHeaders noOrigin = new HttpHeaders();
        noOrigin.set("Access-Control-Request-Method", "GET");
        HttpHeaders noRequestedMethod = new HttpHeaders();
        noRequestedMethod.set("Origin", "https://app.example.com");

        assertThat(call(HttpMethod.OPTIONS, baseUrl() + "/corsy", new HttpHeaders(), null).getStatusCode().value())
            .isEqualTo(401);
        assertThat(call(HttpMethod.OPTIONS, baseUrl() + "/corsy", noOrigin, null).getStatusCode().value())
            .isEqualTo(401);
        assertThat(call(HttpMethod.OPTIONS, baseUrl() + "/corsy", noRequestedMethod, null).getStatusCode().value())
            .isEqualTo(401);
    }

    @Test
    void multiSegmentActualRequestRunsCorsBeforeApiKey() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Origin", "https://app.example.com");

        ResponseEntity<String> response = call(HttpMethod.GET, baseUrl() + "/corsy", headers, null);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Origin")).isEqualTo("*");
    }

    @Test
    void filterRegistrationsAndParsersSupportContextPathAndNestedDataPath() throws Exception {
        assertThat(corsRegistration.getUrlPatterns()).containsExactly("/data/*");
        assertThat(apiKeyRegistration.getUrlPatterns()).containsExactly("/data/*");

        String requestUri = "/app/data/" + fixture.project().getProjectRef() + "/rest/v1/corsy";
        MockHttpServletRequest corsRequest = new MockHttpServletRequest("OPTIONS", requestUri);
        corsRequest.setContextPath("/app");
        corsRequest.addHeader("Origin", "https://app.example.com");
        corsRequest.addHeader("Access-Control-Request-Method", "GET");
        MockHttpServletResponse corsResponse = new MockHttpServletResponse();
        corsRegistration.getFilter().doFilter(corsRequest, corsResponse,
                (request, response) -> { throw new AssertionError("合法预检不得进入后续过滤链"); });
        assertThat(corsResponse.getStatus()).isEqualTo(204);
        assertThat(corsResponse.getHeader("Access-Control-Allow-Origin")).isEqualTo("*");

        MockHttpServletRequest apiKeyRequest = new MockHttpServletRequest("GET", requestUri);
        apiKeyRequest.setContextPath("/app");
        apiKeyRequest.addHeader("apikey", fixture.secretKey());
        MockHttpServletResponse apiKeyResponse = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();
        apiKeyRegistration.getFilter().doFilter(apiKeyRequest, apiKeyResponse,
                (request, response) -> invoked.set(true));
        assertThat(invoked).isTrue();
        assertThat(apiKeyRequest.getAttribute(DataRequestContext.ATTRIBUTE)).isNotNull();
    }

    @Test
    void actualResponseExposesContentRange() {
        HttpHeaders headers = headers(fixture.secretKey(), null);
        headers.set("Origin", "https://app.example.com");

        ResponseEntity<String> response = call(HttpMethod.GET, baseUrl() + "/corsy", headers, null);

        assertThat(response.getHeaders().getFirst("Access-Control-Expose-Headers")).isEqualTo("Content-Range");
        assertThat(response.getHeaders().get("Vary")).contains("Origin");
    }

    @Test
    void unknownProjectRefPreflightPassesWithoutCorsHeaders() {
        ResponseEntity<String> response = call(HttpMethod.OPTIONS,
                "http://localhost:" + port + "/data/nosuchref/rest/v1/x",
                preflight("https://app.example.com", "GET"), null);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void restrictedOriginsEchoOnlyMatch() {
        keyService.updateAllowedOrigins(fixture.project().getId(), List.of("https://ok.example.com"));

        ResponseEntity<String> allowed = call(HttpMethod.OPTIONS, baseUrl() + "/corsy",
                preflight("https://ok.example.com", "GET"), null);
        assertThat(allowed.getHeaders().getFirst("Access-Control-Allow-Origin"))
            .isEqualTo("https://ok.example.com");

        ResponseEntity<String> denied = call(HttpMethod.OPTIONS, baseUrl() + "/corsy",
                preflight("https://evil.example.com", "GET"), null);
        assertThat(denied.getHeaders().getFirst("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void nonArrayAllowedOriginsFailsClosed() {
        projectMapper.update(null, Wrappers.<BaasProject>lambdaUpdate()
            .eq(BaasProject::getId, fixture.project().getId())
            .set(BaasProject::getAllowedOrigins, "{}"));

        ResponseEntity<String> response = call(HttpMethod.OPTIONS, baseUrl() + "/corsy",
                preflight("https://app.example.com", "GET"), null);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void preflightDoesNotTouchProjectDatabase() {
        projectMapper.update(null, Wrappers.<BaasProject>lambdaUpdate()
            .eq(BaasProject::getId, fixture.project().getId())
            .set(BaasProject::getRuntimeDbPasswordCipher, "v1:k1:broken"));

        ResponseEntity<String> response = call(HttpMethod.OPTIONS, baseUrl() + "/corsy",
                preflight("https://app.example.com", "GET"), null);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Origin")).isEqualTo("*");
    }

}
