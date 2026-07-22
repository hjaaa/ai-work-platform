package com.aiwork.common.security.component;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skip-resolve-urls 行为单测(spec §5:数据面路径不解析 Bearer token)。
 *
 * @author ai-work
 * @date 2026/07/21
 */
class AiWorkBearerTokenExtractorTest {

	private static final String TOKEN = "Bearer abc.def.ghi";

	private MockHttpServletRequest dataPlaneRequest() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/data/p1/rest/v1/orders");
		request.addHeader("Authorization", TOKEN);
		return request;
	}

	@Test
	void skipResolveUrlMatchedPathReturnsNull() {
		PermitAllUrlProperties properties = new PermitAllUrlProperties();
		properties.setSkipResolveUrls(List.of("/data/**"));
		AiWorkBearerTokenExtractor extractor = new AiWorkBearerTokenExtractor(properties);

		assertThat(extractor.resolve(dataPlaneRequest())).isNull();
	}

	@Test
	void skipResolveUrlHonorsContextPath() {
		PermitAllUrlProperties properties = new PermitAllUrlProperties();
		properties.setSkipResolveUrls(List.of("/data/**"));
		AiWorkBearerTokenExtractor extractor = new AiWorkBearerTokenExtractor(properties);

		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/data/p1/rest/v1/orders");
		request.setContextPath("/admin");
		request.addHeader("Authorization", TOKEN);

		assertThat(extractor.resolve(request)).isNull();
	}

	@Test
	void defaultEmptyListKeepsExistingBehavior() {
		PermitAllUrlProperties properties = new PermitAllUrlProperties();
		AiWorkBearerTokenExtractor extractor = new AiWorkBearerTokenExtractor(properties);

		assertThat(extractor.resolve(dataPlaneRequest())).isEqualTo("abc.def.ghi");
	}

	@Test
	void nonMatchingPathStillResolvesToken() {
		PermitAllUrlProperties properties = new PermitAllUrlProperties();
		properties.setSkipResolveUrls(List.of("/data/**"));
		AiWorkBearerTokenExtractor extractor = new AiWorkBearerTokenExtractor(properties);

		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/studio/projects");
		request.addHeader("Authorization", TOKEN);

		assertThat(extractor.resolve(request)).isEqualTo("abc.def.ghi");
	}

}
