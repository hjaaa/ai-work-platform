package com.aiwork.common.feign.interceptor;

import feign.RequestInterceptor;
import org.springframework.http.HttpHeaders;

/**
 * @author lengleng
 * @date 2024/3/15
 * <p>
 * http connection close
 */
public class AiWorkFeignRequestCloseInterceptor implements RequestInterceptor {

	/**
	 * set connection close
	 * @param template
	 */
	@Override
	public void apply(feign.RequestTemplate template) {
		template.header(HttpHeaders.CONNECTION, "close");
	}

}
