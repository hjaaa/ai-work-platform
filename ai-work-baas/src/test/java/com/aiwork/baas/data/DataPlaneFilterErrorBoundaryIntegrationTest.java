package com.aiwork.baas.data;

import com.aiwork.baas.mapper.BaasApiKeyMapper;
import com.aiwork.baas.mapper.BaasProjectMapper;
import com.aiwork.baas.support.DataPlaneIntegrationTestSupport;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/** 数据面过滤器链意外异常的 HTTP 边界测试。 */
class DataPlaneFilterErrorBoundaryIntegrationTest extends DataPlaneIntegrationTestSupport {

    @MockitoSpyBean
    private BaasApiKeyMapper apiKeyMapper;

    @MockitoSpyBean
    private BaasProjectMapper projectMapper;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void apiKeyMapperRuntimeExceptionReturnsSanitizedDataErrorWithoutOrigin() {
        doThrow(new IllegalStateException("sensitive api key mapper failure"))
            .when(apiKeyMapper).selectOne(any());
        HttpHeaders headers = new HttpHeaders();
        headers.set("apikey", "sec_mapper_failure");

        ResponseEntity<String> response = call(HttpMethod.GET, baseUrl() + "/unknown", headers, null);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(json(response).get("code").asText()).isEqualTo("INTERNAL");
        assertThat(response.getBody()).doesNotContain("sensitive api key mapper failure");
    }

    @Test
    void corsProjectMapperRuntimeExceptionReturnsSanitizedDataErrorWithOrigin() {
        doThrow(new IllegalStateException("sensitive project mapper failure"))
            .when(projectMapper).selectOne(any());
        HttpHeaders headers = new HttpHeaders();
        headers.set("Origin", "https://app.example.com");

        ResponseEntity<String> response = call(HttpMethod.GET, baseUrl() + "/unknown", headers, null);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(json(response).get("code").asText()).isEqualTo("INTERNAL");
        assertThat(response.getBody()).doesNotContain("sensitive project mapper failure");
    }

    @Test
    @SuppressWarnings("unchecked")
    void outerBoundaryCoversNestedDataPathAndDoesNotCatchError() {
        FilterRegistrationBean<Filter> registration = (FilterRegistrationBean<Filter>)applicationContext
            .getBean("dataPlaneErrorBoundaryFilterRegistration", FilterRegistrationBean.class);
        assertThat(registration.getOrder()).isLessThan(10);
        assertThat(registration.getUrlPatterns()).containsExactly("/data/*");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/data/ref/rest/v1/table");
        request.setContextPath("/app");
        MockHttpServletResponse response = new MockHttpServletResponse();
        LinkageError fatal = new LinkageError("fatal");

        assertThatThrownBy(() -> registration.getFilter().doFilter(request, response,
                (servletRequest, servletResponse) -> { throw fatal; }))
            .isSameAs(fatal);
    }

}
