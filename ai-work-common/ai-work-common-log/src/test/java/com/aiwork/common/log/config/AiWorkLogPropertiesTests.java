package com.aiwork.common.log.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiWorkLogPropertiesTests {

	@Test
	void shouldProvideDefaultExcludeFields() {
		AiWorkLogProperties properties = new AiWorkLogProperties();

		assertThat(properties.getExcludeFields()).containsExactlyElementsOf(
				List.of("password", "mobile", "idcard", "phone", "accessSecret", "tokenId", "sign"));
	}

}
