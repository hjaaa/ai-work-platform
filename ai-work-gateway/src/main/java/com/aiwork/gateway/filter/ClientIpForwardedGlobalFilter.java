/*
 *    Copyright (c) 2018-2026, lengleng All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 * Neither the name of the pig4cloud.com developer nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 * Author: lengleng (wangiegie@gmail.com)
 */

package com.aiwork.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * 终端用户数据面可信客户端 IP 覆盖(spec §12.2/§13):网关是唯一边缘,直接对端 socket 地址即真实客户端 IP。
 * 剥离客户端可伪造的入站 X-Forwarded-For/Forwarded,把 X-Forwarded-For 钉死为真实对端地址;下游 BaaS
 * 仅信任网关 /32 后据此取限速/审计 IP。不依赖 SCG XForwardedHeadersFilter 的 trusted-proxies 语义
 * (5.0 起不配 trusted-proxies 即不激活),行为确定、可测。仅覆盖 IP 相关头,不动 X-Forwarded-Host/Proto/Prefix
 * (下游 URL 重建与 swagger 前缀仍需)。
 *
 * @author ai-work
 * @date 2026/07/22
 */
@Component
public class ClientIpForwardedGlobalFilter implements GlobalFilter, Ordered {

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
		String clientIp = (remote == null || remote.getAddress() == null) ? null
				: remote.getAddress().getHostAddress();
		ServerHttpRequest request = exchange.getRequest().mutate().headers(headers -> {
			// 丢弃客户端自带的 IP 转发头,杜绝 XFF 伪造
			headers.remove("X-Forwarded-For");
			headers.remove("Forwarded");
			if (clientIp != null) {
				headers.set("X-Forwarded-For", clientIp);
			}
		}).build();
		return chain.filter(exchange.mutate().request(request).build());
	}

	@Override
	public int getOrder() {
		// 最早执行:任何后续过滤器/路由都看不到被伪造的入站 XFF
		return Ordered.HIGHEST_PRECEDENCE + 100;
	}

}
