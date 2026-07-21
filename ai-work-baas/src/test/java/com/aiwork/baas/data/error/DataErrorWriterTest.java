package com.aiwork.baas.data.error;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据面错误体输出形状(spec §11:{code,message,details,hint},不复用平台 R)。
 *
 * @author ai-work
 * @date 2026/07/21
 */
class DataErrorWriterTest {

    private final DataErrorWriter writer = new DataErrorWriter(JsonMapper.builder().build());

    @Test
    void writesPostgrestStyleBody() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(response, DataApiException.notFound("表不存在"));

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString())
            .isEqualTo("{\"code\":\"NOT_FOUND\",\"message\":\"表不存在\",\"details\":null,\"hint\":null}");
    }

    @Test
    void writesHintWhenPresent() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(response,
                DataApiException.forbidden("表暂不可用", "表结构变更进行中,请稍后重试"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"hint\":\"表结构变更进行中,请稍后重试\"");
    }

    @Test
    void committedResponseIsLeftUntouched() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.getOutputStream().print("partial");
        response.flushBuffer();

        writer.write(response, DataApiException.internal("boom"));

        assertThat(response.getContentAsString()).isEqualTo("partial");
    }

    @Test
    void factoryStatusMapping() {
        assertThat(DataApiException.badRequest("x").status()).isEqualTo(400);
        assertThat(DataApiException.unauthorized("x").status()).isEqualTo(401);
        assertThat(DataApiException.forbidden("x", null).status()).isEqualTo(403);
        assertThat(DataApiException.notFound("x").status()).isEqualTo(404);
        assertThat(DataApiException.conflict("x").status()).isEqualTo(409);
        assertThat(DataApiException.payloadTooLarge("x").status()).isEqualTo(413);
        assertThat(DataApiException.tooManyRequests("x").status()).isEqualTo(429);
        assertThat(DataApiException.internal("x").status()).isEqualTo(500);
    }

}
