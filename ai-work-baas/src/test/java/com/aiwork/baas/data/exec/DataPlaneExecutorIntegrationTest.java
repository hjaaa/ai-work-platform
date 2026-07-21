package com.aiwork.baas.data.exec;

import com.aiwork.baas.controller.dto.AclRoleDTO;
import com.aiwork.baas.data.config.DataPlaneProperties;
import com.aiwork.baas.data.context.DataRequestContext;
import com.aiwork.baas.data.context.DataRole;
import com.aiwork.baas.data.error.DataApiException;
import com.aiwork.baas.data.query.ParsedQuery;
import com.aiwork.baas.data.query.QueryParser;
import com.aiwork.baas.data.rest.PreferHeader;
import com.aiwork.baas.datasource.ProjectDataSourceRegistry;
import com.aiwork.baas.support.DataPlaneIntegrationTestSupport;
import com.alibaba.druid.pool.DruidDataSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 执行层集成测试:真流式配置、事务、representation 算法(spec §7.5/§7.1/§14)。
 *
 * @author ai-work
 * @date 2026/07/21
 */
class DataPlaneExecutorIntegrationTest extends DataPlaneIntegrationTestSupport {

    @Autowired
    private DataPlaneExecutor executor;

    @Autowired
    private QueryParser queryParser;

    @Autowired
    private ProjectDataSourceRegistry registry;

    @Autowired
    private DataPlaneProperties properties;

    @Autowired
    @Qualifier("dataResponsePermits")
    private Semaphore responsePermits;

    private DataRequestContext serviceCtx() {
        return new DataRequestContext(fixture.project(), DataRole.SERVICE_ROLE, null);
    }

    private DataRequestContext authenticatedCtx() {
        return new DataRequestContext(fixture.project(), DataRole.AUTHENTICATED, 42L);
    }

    private ParsedQuery query(Map<String, String[]> params) {
        return queryParser.parse(params);
    }

    private void createOrdersTable() {
        createDataTable("orders", List.of(col("title", "varchar", 64, null), col("qty", "int", null, null)));
    }

    @Test
    void registryUrlEnablesServerSideCursor() {
        String url = registry.execute(fixture.project(), ds -> ((DruidDataSource)ds).getUrl());

        assertThat(url).contains("useCursorFetch=true");
    }

    @Test
    void streamingStatementConfiguration() throws Exception {
        createOrdersTable();
        registry.execute(fixture.project(), ds -> {
            try (Connection connection = ds.getConnection();
                    PreparedStatement statement = DataPlaneExecutor.streamingStatement(connection,
                            "SELECT `id` FROM `orders`", 5, 100)) {
                assertThat(statement.getResultSetType()).isEqualTo(ResultSet.TYPE_FORWARD_ONLY);
                assertThat(statement.getResultSetConcurrency()).isEqualTo(ResultSet.CONCUR_READ_ONLY);
                assertThat(statement.getFetchSize()).isEqualTo(100);
                assertThat(statement.getQueryTimeout()).isEqualTo(5);
                return null;
            }
            catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    @Test
    void postThenGetRoundTrip() throws Exception {
        int initialPermits = responsePermits.availablePermits();
        createOrdersTable();
        MockHttpServletResponse postResponse = new MockHttpServletResponse();
        executor.executePost(serviceCtx(), "orders",
                dataPlaneObjectMapper.readTree("[{\"title\":\"a\",\"qty\":1},{\"title\":\"b\",\"qty\":2}]"),
                PreferHeader.parse(null), postResponse);

        assertThat(postResponse.getStatus()).isEqualTo(201);
        JsonNode insertedIds = dataPlaneObjectMapper.readTree(postResponse.getContentAsString());
        assertThat(insertedIds).hasSize(2);
        assertThat(insertedIds.get(0).get("id").isNumber()).isTrue();
        assertThat(insertedIds.get(1).get("id").asLong()).isGreaterThan(insertedIds.get(0).get("id").asLong());

        MockHttpServletResponse getResponse = new MockHttpServletResponse();
        executor.executeGet(serviceCtx(), "orders", query(Map.of("order", new String[] { "qty.asc" })),
                PreferHeader.parse("count=exact"), getResponse);

        assertThat(getResponse.getStatus()).isEqualTo(200);
        assertThat(getResponse.getHeader("Content-Range")).isEqualTo("0-1/2");
        assertThat(dataPlaneObjectMapper.readTree(getResponse.getContentAsString()).get(0).get("title").asText())
            .isEqualTo("a");
        assertThat(responsePermits.availablePermits()).isEqualTo(initialPermits);
    }

    @Test
    void postRepresentationOversizeRollsBackAndReleasesPermit() throws Exception {
        createOrdersTable();
        int initialPermits = responsePermits.availablePermits();
        long previousMaxBytes = properties.getResponseMaxBytes();
        properties.setResponseMaxBytes(32L);
        MockHttpServletResponse post = new MockHttpServletResponse();

        try {
            assertThatThrownBy(() -> executor.executePost(serviceCtx(), "orders",
                    dataPlaneObjectMapper.readTree("{\"title\":\"" + "x".repeat(32) + "\",\"qty\":1}"),
                    PreferHeader.parse("return=representation"), post))
                .isInstanceOf(DataApiException.class)
                .satisfies(e -> assertThat(((DataApiException)e).status()).isEqualTo(413));
            assertThat(post.isCommitted()).isFalse();
            assertThat(responsePermits.availablePermits()).isEqualTo(initialPermits);
        }
        finally {
            properties.setResponseMaxBytes(previousMaxBytes);
        }

        MockHttpServletResponse get = new MockHttpServletResponse();
        executor.executeGet(serviceCtx(), "orders", query(Map.of()), PreferHeader.parse(null), get);
        assertThat(dataPlaneObjectMapper.readTree(get.getContentAsString())).isEmpty();
    }

    @Test
    void patchRepresentationReturnsChangedRowsByPrimaryKey() throws Exception {
        createOrdersTable();
        MockHttpServletResponse seed = new MockHttpServletResponse();
        executor.executePost(serviceCtx(), "orders",
                dataPlaneObjectMapper.readTree("[{\"title\":\"x\",\"qty\":1},{\"title\":\"x\",\"qty\":2}]"),
                PreferHeader.parse(null), seed);

        // 修改过滤列自身:qty=eq.1 → qty=9;按原过滤条件重查会漏行,按捕获主键回查不会
        MockHttpServletResponse patch = new MockHttpServletResponse();
        executor.executePatch(serviceCtx(), "orders", query(Map.of("qty", new String[] { "eq.1" })),
                dataPlaneObjectMapper.readTree("{\"qty\":9}"), PreferHeader.parse("return=representation"), patch);

        assertThat(patch.getStatus()).isEqualTo(200);
        com.fasterxml.jackson.databind.JsonNode rows = dataPlaneObjectMapper.readTree(patch.getContentAsString());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("qty").asInt()).isEqualTo(9);
    }

    @Test
    void patchRepresentationChecksClosingByteBeforeCommit() throws Exception {
        int initialPermits = responsePermits.availablePermits();
        createOrdersTable();
        MockHttpServletResponse seed = new MockHttpServletResponse();
        executor.executePost(serviceCtx(), "orders", dataPlaneObjectMapper.readTree("{\"title\":\"x\",\"qty\":1}"),
                PreferHeader.parse(null), seed);
        long id = dataPlaneObjectMapper.readTree(seed.getContentAsString()).get(0).get("id").asLong();
        String representation = "[{\"id\":" + id + ",\"title\":\"x\",\"qty\":9}]";
        long previousMaxBytes = properties.getResponseMaxBytes();
        properties.setResponseMaxBytes(representation.getBytes(StandardCharsets.UTF_8).length - 1L);

        MockHttpServletResponse patch = new MockHttpServletResponse();
        try {
            assertThatThrownBy(() -> executor.executePatch(serviceCtx(), "orders",
                    query(Map.of("qty", new String[] { "eq.1" })), dataPlaneObjectMapper.readTree("{\"qty\":9}"),
                    PreferHeader.parse("return=representation"), patch))
                .isInstanceOf(DataApiException.class)
                .satisfies(e -> assertThat(((DataApiException)e).status()).isEqualTo(413));
            assertThat(patch.isCommitted()).isFalse();
            assertThat(responsePermits.availablePermits()).isEqualTo(initialPermits);
        }
        finally {
            properties.setResponseMaxBytes(previousMaxBytes);
        }

        MockHttpServletResponse get = new MockHttpServletResponse();
        executor.executeGet(serviceCtx(), "orders", query(Map.of("id", new String[] { "eq." + id })),
                PreferHeader.parse(null), get);
        assertThat(dataPlaneObjectMapper.readTree(get.getContentAsString()).get(0).get("qty").asInt()).isEqualTo(1);
    }

    @Test
    void patchRepresentationRejectsOneThousandAndOneRowsWithoutUpdating() throws Exception {
        createOrdersTable();
        int representationLimit = properties.getRepresentationMaxRows();
        assertThat(properties.getBatchMaxRows()).isGreaterThanOrEqualTo(representationLimit);
        ArrayNode batch = dataPlaneObjectMapper.createArrayNode();
        for (int index = 0; index < representationLimit; index++) {
            batch.addObject().put("title", "x").put("qty", 1);
        }
        executor.executePost(serviceCtx(), "orders", batch, PreferHeader.parse(null),
                new MockHttpServletResponse());
        executor.executePost(serviceCtx(), "orders", dataPlaneObjectMapper.readTree("{\"title\":\"x\",\"qty\":1}"),
                PreferHeader.parse(null), new MockHttpServletResponse());

        MockHttpServletResponse patch = new MockHttpServletResponse();
        assertThatThrownBy(() -> executor.executePatch(serviceCtx(), "orders",
                query(Map.of("title", new String[] { "eq.x" })), dataPlaneObjectMapper.readTree("{\"qty\":9}"),
                PreferHeader.parse("return=representation"), patch))
            .isInstanceOf(DataApiException.class)
            .satisfies(e -> assertThat(((DataApiException)e).status()).isEqualTo(400));
        assertThat(patch.isCommitted()).isFalse();

        MockHttpServletResponse changed = new MockHttpServletResponse();
        executor.executeGet(serviceCtx(), "orders", query(Map.of("qty", new String[] { "eq.9" })),
                PreferHeader.parse("count=exact"), changed);
        assertThat(dataPlaneObjectMapper.readTree(changed.getContentAsString())).isEmpty();
        assertThat(changed.getHeader("Content-Range")).isEqualTo("*/0");
    }

    @Test
    void exhaustedPermitsRejectGetImmediately() {
        createOrdersTable();
        int permits = responsePermits.availablePermits();
        assertThat(permits).isPositive();
        assertThat(responsePermits.tryAcquire(permits)).isTrue();

        try {
            assertThatThrownBy(() -> executor.executeGet(serviceCtx(), "orders", query(Map.of()),
                    PreferHeader.parse(null), new MockHttpServletResponse()))
                .isInstanceOf(DataApiException.class)
                .satisfies(e -> assertThat(((DataApiException)e).status()).isEqualTo(429));
        }
        finally {
            responsePermits.release(permits);
        }
    }

    @Test
    void nonRepresentationPostDoesNotAcquirePermit() throws Exception {
        createOrdersTable();
        int permits = responsePermits.availablePermits();
        assertThat(permits).isPositive();
        assertThat(responsePermits.tryAcquire(permits)).isTrue();

        try {
            MockHttpServletResponse post = new MockHttpServletResponse();
            executor.executePost(serviceCtx(), "orders",
                    dataPlaneObjectMapper.readTree("{\"title\":\"x\",\"qty\":1}"), PreferHeader.parse(null), post);
            assertThat(post.getStatus()).isEqualTo(201);
        }
        finally {
            responsePermits.release(permits);
        }
    }

    @Test
    void postRepresentationWithoutSelectPermissionHasNoSideEffect() throws Exception {
        createOrdersTable();
        openAcl("orders", allClosed(), new AclRoleDTO(false, true, false, false), null);

        assertThatThrownBy(() -> executor.executePost(authenticatedCtx(), "orders",
                dataPlaneObjectMapper.readTree("{\"title\":\"x\",\"qty\":1}"),
                PreferHeader.parse("return=representation"), new MockHttpServletResponse()))
            .isInstanceOf(DataApiException.class)
            .satisfies(e -> assertThat(((DataApiException)e).status()).isEqualTo(403));

        MockHttpServletResponse get = new MockHttpServletResponse();
        executor.executeGet(serviceCtx(), "orders", query(Map.of()), PreferHeader.parse(null), get);
        assertThat(dataPlaneObjectMapper.readTree(get.getContentAsString())).isEmpty();
    }

    @Test
    void patchRepresentationWithoutSelectPermissionHasNoSideEffect() throws Exception {
        createOrdersTable();
        executor.executePost(serviceCtx(), "orders", dataPlaneObjectMapper.readTree("{\"title\":\"x\",\"qty\":1}"),
                PreferHeader.parse(null), new MockHttpServletResponse());
        openAcl("orders", allClosed(), new AclRoleDTO(false, false, true, false), null);

        assertThatThrownBy(() -> executor.executePatch(authenticatedCtx(), "orders",
                query(Map.of("qty", new String[] { "eq.1" })), dataPlaneObjectMapper.readTree("{\"qty\":9}"),
                PreferHeader.parse("return=representation"), new MockHttpServletResponse()))
            .isInstanceOf(DataApiException.class)
            .satisfies(e -> assertThat(((DataApiException)e).status()).isEqualTo(403));

        MockHttpServletResponse get = new MockHttpServletResponse();
        executor.executeGet(serviceCtx(), "orders", query(Map.of()), PreferHeader.parse(null), get);
        assertThat(dataPlaneObjectMapper.readTree(get.getContentAsString()).get(0).get("qty").asInt()).isEqualTo(1);
    }

    @Test
    void patchWithoutRepresentationReturnsCount() throws Exception {
        createOrdersTable();
        MockHttpServletResponse seed = new MockHttpServletResponse();
        executor.executePost(serviceCtx(), "orders", dataPlaneObjectMapper.readTree("{\"title\":\"x\",\"qty\":1}"),
                PreferHeader.parse(null), seed);

        MockHttpServletResponse patch = new MockHttpServletResponse();
        executor.executePatch(serviceCtx(), "orders", query(Map.of("qty", new String[] { "eq.1" })),
                dataPlaneObjectMapper.readTree("{\"qty\":2}"), PreferHeader.parse(null), patch);

        assertThat(patch.getStatus()).isEqualTo(200);
        assertThat(patch.getContentAsString()).isEqualTo("{\"count\":1}");
    }

    @Test
    void deleteReturnsCountAndZeroMatchesIsOk() throws Exception {
        createOrdersTable();
        MockHttpServletResponse delete = new MockHttpServletResponse();
        executor.executeDelete(serviceCtx(), "orders", query(Map.of("qty", new String[] { "eq.777" })), delete);

        assertThat(delete.getStatus()).isEqualTo(200);
        assertThat(delete.getContentAsString()).isEqualTo("{\"count\":0}");
    }

    @Test
    void duplicateKeyMapsTo409() throws Exception {
        int initialPermits = responsePermits.availablePermits();
        createDataTable("uniq", List.of(
                new com.aiwork.baas.controller.dto.ColumnDefinitionDTO("email", "varchar", 64, null, true, null,
                        true, false, null)));
        MockHttpServletResponse first = new MockHttpServletResponse();
        executor.executePost(serviceCtx(), "uniq", dataPlaneObjectMapper.readTree("{\"email\":\"a@b.c\"}"),
                PreferHeader.parse(null), first);

        assertThatThrownBy(() -> executor.executePost(serviceCtx(), "uniq",
                dataPlaneObjectMapper.readTree("{\"email\":\"a@b.c\"}"),
                PreferHeader.parse("return=representation"),
                new MockHttpServletResponse()))
            .isInstanceOf(DataApiException.class)
            .satisfies(e -> assertThat(((DataApiException)e).status()).isEqualTo(409));
        assertThat(responsePermits.availablePermits()).isEqualTo(initialPermits);
    }

    @Test
    void batchInsertIsAtomic() throws Exception {
        createDataTable("uniq2", List.of(
                new com.aiwork.baas.controller.dto.ColumnDefinitionDTO("email", "varchar", 64, null, true, null,
                        true, false, null)));
        // 第二行与第一行冲突 → 整体回滚
        assertThatThrownBy(() -> executor.executePost(serviceCtx(), "uniq2",
                dataPlaneObjectMapper.readTree("[{\"email\":\"x@y.z\"},{\"email\":\"x@y.z\"}]"),
                PreferHeader.parse(null), new MockHttpServletResponse()))
            .isInstanceOf(DataApiException.class);

        MockHttpServletResponse get = new MockHttpServletResponse();
        executor.executeGet(serviceCtx(), "uniq2", query(Map.of()), PreferHeader.parse(null), get);
        assertThat(dataPlaneObjectMapper.readTree(get.getContentAsString())).isEmpty();
    }

}
