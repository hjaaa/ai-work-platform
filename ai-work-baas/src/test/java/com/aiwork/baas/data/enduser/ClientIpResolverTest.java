package com.aiwork.baas.data.enduser;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 客户端 IP 判定(spec §12.2):仅 remoteAddr 属可信代理列表时读取 XFF。
 */
class ClientIpResolverTest {

    private static HttpServletRequest request(String remoteAddr, String xff) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        if (xff != null) {
            request.addHeader("X-Forwarded-For", xff);
        }
        return request;
    }

    private static ClientIpResolver resolver(List<String> trustedProxies) {
        AuthProperties properties = new AuthProperties();
        properties.setTrustedProxies(trustedProxies);
        return new ClientIpResolver(properties);
    }

    @Test
    void untrustedRemoteIgnoresForgedXff() {
        // 伪造 XFF 不影响判定(§14:XFF 信任边界)
        assertThat(resolver(List.of()).resolve(request("203.0.113.7", "10.0.0.1, 10.0.0.2")))
            .isEqualTo("203.0.113.7");
    }

    @Test
    void trustedExactMatchUsesXff() {
        assertThat(resolver(List.of("172.18.0.2")).resolve(request("172.18.0.2", "198.51.100.9")))
            .isEqualTo("198.51.100.9");
    }

    @Test
    void trustedCidrMatchUsesRightmostXffEntry() {
        // 网关覆盖后为单值;若链上有多段,取最右(可信代理写入的一段)
        assertThat(resolver(List.of("172.18.0.0/30")).resolve(request("172.18.0.2", "1.2.3.4, 198.51.100.9")))
            .isEqualTo("198.51.100.9");
    }

    @Test
    void trustedWithoutXffFallsBackToRemote() {
        assertThat(resolver(List.of("172.18.0.2/32")).resolve(request("172.18.0.2", null)))
            .isEqualTo("172.18.0.2");
    }

    @Test
    void cidrBoundary() {
        ClientIpResolver resolver = resolver(List.of("10.1.2.0/24"));
        assertThat(resolver.resolve(request("10.1.2.255", "9.9.9.9"))).isEqualTo("9.9.9.9");
        assertThat(resolver.resolve(request("10.1.3.1", "9.9.9.9"))).isEqualTo("10.1.3.1");
    }

}
