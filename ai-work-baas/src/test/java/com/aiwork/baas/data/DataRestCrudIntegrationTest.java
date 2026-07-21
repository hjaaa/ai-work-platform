package com.aiwork.baas.data;

import com.aiwork.baas.data.config.DataPlaneProperties;
import com.aiwork.baas.datasource.ProjectDataSourceRegistry;
import com.aiwork.baas.support.DataPlaneIntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REST 语义细则集成测试(spec §7.1 全量,含错误体形状与序列化隔离)。
 *
 * @author ai-work
 * @date 2026/07/21
 */
class DataRestCrudIntegrationTest extends DataPlaneIntegrationTestSupport {

    @Autowired
    private DataPlaneProperties properties;

    @Autowired
    private ProjectDataSourceRegistry registry;

    private HttpHeaders serviceHeaders;

    @BeforeEach
    void setUpTable() {
        createDataTable("items", List.of(col("title", "varchar", 64, null), col("qty", "int", null, null),
                col("price", "decimal", 10, 2), col("active", "boolean", null, null),
                col("meta", "json", null, null), col("born", "datetime", null, null)));
        serviceHeaders = headers(fixture.secretKey(), null);
    }

    @AfterEach
    void closeProjectPool() {
        registry.blockAndDrain(fixture.project().getProjectRef());
    }

    private String itemsUrl(String queryString) {
        return baseUrl() + "/items" + (queryString == null ? "" : "?" + queryString);
    }

    @Test
    void postSingleReturns201WithIdArray() {
        ResponseEntity<String> response = call(HttpMethod.POST, itemsUrl(null), serviceHeaders,
                "{\"title\":\"a\",\"qty\":1}");

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        // bigint 输出 JSON number(非字符串):正则断言原文
        assertThat(response.getBody()).matches("\\[\\{\"id\":\\d+}]");
    }

    @Test
    void getSupportsOperatorsOrderPagination() {
        call(HttpMethod.POST, itemsUrl(null), serviceHeaders,
                "[{\"title\":\"a\",\"qty\":1},{\"title\":\"ab\",\"qty\":5},{\"title\":\"b\",\"qty\":9}]");

        ResponseEntity<String> gte = call(HttpMethod.GET, itemsUrl("qty=gte.5&order=qty.desc"), serviceHeaders, null);
        JsonNode rows = json(gte);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("qty").asInt()).isEqualTo(9);

        // URI 中字面 % 必须编码为 %25;Servlet 解码后 QueryParser 收到 a%
        JsonNode like = json(call(HttpMethod.GET, itemsUrl("title=like.a%25"), serviceHeaders, null));
        assertThat(like).hasSize(2);

        JsonNode in = json(call(HttpMethod.GET, itemsUrl("qty=in.(1,9)"), serviceHeaders, null));
        assertThat(in).hasSize(2);

        JsonNode paged = json(call(HttpMethod.GET, itemsUrl("order=qty.asc&limit=1&offset=1"), serviceHeaders, null));
        assertThat(paged).hasSize(1);
        assertThat(paged.get(0).get("qty").asInt()).isEqualTo(5);

        JsonNode selected = json(call(HttpMethod.GET, itemsUrl("select=id,title&qty=eq.1"), serviceHeaders, null));
        assertThat(selected.get(0).has("qty")).isFalse();
    }

    @Test
    void repeatedFilterParametersAreCombinedWithAnd() {
        call(HttpMethod.POST, itemsUrl(null), serviceHeaders,
                "[{\"title\":\"a\",\"qty\":1},{\"title\":\"b\",\"qty\":5},{\"title\":\"c\",\"qty\":9}]");

        JsonNode rows = json(call(HttpMethod.GET, itemsUrl("qty=gte.1&qty=lte.5"), serviceHeaders, null));

        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> assertThat(row.get("qty").asInt()).isBetween(1, 5));
    }

    @Test
    void zeroMatchReturnsEmptyArrayNot404() {
        ResponseEntity<String> response = call(HttpMethod.GET, itemsUrl("qty=eq.12345"), serviceHeaders, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("[]");
    }

    @Test
    void unknownTableIs404WithErrorBody() {
        ResponseEntity<String> response = call(HttpMethod.GET, baseUrl() + "/nope", serviceHeaders, null);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(json(response).get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(json(response).has("hint")).isTrue();
    }

    @Test
    void patchDeleteWithoutFilterRejected() {
        assertThat(call(HttpMethod.PATCH, itemsUrl(null), serviceHeaders, "{\"qty\":1}").getStatusCode().value())
            .isEqualTo(400);
        assertThat(call(HttpMethod.DELETE, itemsUrl(null), serviceHeaders, null).getStatusCode().value())
            .isEqualTo(400);
    }

    @Test
    void bodyWithIdRejectedEvenForServiceRole() {
        assertThat(call(HttpMethod.POST, itemsUrl(null), serviceHeaders, "{\"id\":1,\"qty\":1}").getStatusCode()
            .value()).isEqualTo(400);
        assertThat(call(HttpMethod.PATCH, itemsUrl("qty=eq.1"), serviceHeaders, "{\"id\":2}").getStatusCode()
            .value()).isEqualTo(400);
    }

    @Test
    void countExactSetsContentRange() {
        call(HttpMethod.POST, itemsUrl(null), serviceHeaders, "[{\"qty\":1},{\"qty\":2},{\"qty\":3}]");
        HttpHeaders withPrefer = new HttpHeaders();
        withPrefer.putAll(serviceHeaders);
        withPrefer.set("Prefer", "count=exact");

        ResponseEntity<String> response = call(HttpMethod.GET, itemsUrl("limit=2"), withPrefer, null);

        assertThat(response.getHeaders().getFirst("Content-Range")).isEqualTo("0-1/3");
    }

    @Test
    void lineProtocolRoundTrip() {
        String body = "{\"title\":\"t\",\"qty\":7,\"price\":\"19.99\",\"active\":true,"
                + "\"meta\":{\"tags\":[1,2]},\"born\":\"2026-07-21 09:05:03\"}";
        call(HttpMethod.POST, itemsUrl(null), serviceHeaders, body);

        JsonNode row = json(call(HttpMethod.GET, itemsUrl("qty=eq.7"), serviceHeaders, null)).get(0);

        assertThat(row.get("price").isNumber()).isTrue();
        assertThat(row.get("price").decimalValue()).isEqualByComparingTo("19.99");
        assertThat(row.get("active").isBoolean()).isTrue();
        assertThat(row.get("meta").isObject()).isTrue();
        assertThat(row.get("meta").get("tags")).hasSize(2);
        assertThat(row.get("born").asText()).isEqualTo("2026-07-21 09:05:03");
    }

    @Test
    void explicitNullVersusMissingField() {
        // active 显式 null → SQL NULL;price 缺失 → 走默认值(无默认值可空列 → NULL)
        call(HttpMethod.POST, itemsUrl(null), serviceHeaders, "{\"qty\":11,\"active\":null}");

        JsonNode row = json(call(HttpMethod.GET, itemsUrl("qty=eq.11"), serviceHeaders, null)).get(0);
        assertThat(row.get("active").isNull()).isTrue();
        assertThat(row.get("price").isNull()).isTrue();
    }

    @Test
    void batchKeySetMismatchRejected() {
        ResponseEntity<String> response = call(HttpMethod.POST, itemsUrl(null), serviceHeaders,
                "[{\"qty\":1},{\"qty\":2,\"title\":\"x\"}]");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void xssPayloadStoredAndReturnedVerbatim() {
        String html = "<script>alert('x')</script>&nbsp;";
        call(HttpMethod.POST, itemsUrl(null), serviceHeaders,
                "{\"qty\":21,\"title\":\"" + html.replace("\"", "\\\"") + "\"}");

        JsonNode row = json(call(HttpMethod.GET, itemsUrl("qty=eq.21"), serviceHeaders, null)).get(0);

        assertThat(row.get("title").asText()).isEqualTo(html);
    }

    @Test
    void postRepresentationReturnsFullRows() {
        HttpHeaders withPrefer = new HttpHeaders();
        withPrefer.putAll(serviceHeaders);
        withPrefer.set("Prefer", "return=representation");

        ResponseEntity<String> response = call(HttpMethod.POST, itemsUrl(null), withPrefer,
                "{\"title\":\"rep\",\"qty\":31}");

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        JsonNode rows = json(response);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("title").asText()).isEqualTo("rep");
        assertThat(rows.get(0).get("id").isNumber()).isTrue();
    }

    @Test
    void deleteRepresentationRejected() {
        HttpHeaders withPrefer = new HttpHeaders();
        withPrefer.putAll(serviceHeaders);
        withPrefer.set("Prefer", "return=representation");

        assertThat(call(HttpMethod.DELETE, itemsUrl("qty=eq.1"), withPrefer, null).getStatusCode().value())
            .isEqualTo(400);
    }

    @Test
    void chunkedBodyOverLimitIsRejectedByControllerFallback() throws Exception {
        byte[] payload = ("{\"title\":\"" + "x".repeat((int)properties.getBodyMaxBytes()) + "\"}")
            .getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(itemsUrl(null)))
            .header("apikey", fixture.secretKey())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(payload)))
            .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
            .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(dataPlaneObjectMapper.readTree(response.body()).get("code").asText())
            .isEqualTo("PAYLOAD_TOO_LARGE");
    }

    @Test
    void systemTablesInvisible() {
        assertThat(call(HttpMethod.GET, baseUrl() + "/_users", serviceHeaders, null).getStatusCode().value())
            .isEqualTo(404);
    }

}
