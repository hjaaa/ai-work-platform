package com.aiwork.gateway.filter;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientIpForwardedGlobalFilterTest {

	private final ClientIpForwardedGlobalFilter filter = new ClientIpForwardedGlobalFilter();

	@Test
	void stripsForgedForwardingHeadersAndPinsRealPeerIp() {
		MockServerHttpRequest request = MockServerHttpRequest.get("/data/ref0001/auth/v1/token")
			.header("X-Forwarded-For", "9.9.9.9") // 客户端伪造
			.header("Forwarded", "for=9.9.9.9")
			.remoteAddress(new InetSocketAddress("203.0.113.7", 44321))
			.build();
		MockServerWebExchange exchange = MockServerWebExchange.from(request);
		GatewayFilterChain chain = mock(GatewayFilterChain.class);
		ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
		when(chain.filter(captor.capture())).thenReturn(Mono.empty());

		filter.filter(exchange, chain).block();

		HttpHeaders downstream = captor.getValue().getRequest().getHeaders();
		assertThat(downstream.get("X-Forwarded-For")).containsExactly("203.0.113.7");
		assertThat(downstream.getFirst("Forwarded")).isNull();
	}

	@Test
	void runsBeforeApplicationGlobalFilters() {
		assertThat(filter.getOrder()).isLessThan(10);
	}

}
