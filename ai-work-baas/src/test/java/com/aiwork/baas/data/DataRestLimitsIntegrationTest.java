package com.aiwork.baas.data;

import com.aiwork.baas.data.meta.DataMetadataService;
import com.aiwork.baas.datasource.ProjectDataSourceRegistry;
import com.aiwork.baas.mapper.BaasTableMapper;
import com.aiwork.baas.support.DataPlaneIntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 资源限制与并发(spec §13/§7.5:请求体/响应体上限、信号量、MDL 屏障阻塞语义)。
 *
 * @author ai-work
 * @date 2026/07/21
 */
@TestPropertySource(properties = { "baas.data.response-max-bytes=4096", "baas.data.query-timeout-seconds=1" })
class DataRestLimitsIntegrationTest extends DataPlaneIntegrationTestSupport {

    @Autowired
    @Qualifier("dataResponsePermits")
    private Semaphore permits;

    @MockitoSpyBean
    private DataMetadataService metadataService;

    @Autowired
    private BaasTableMapper tableMapper;

    @Autowired
    private ProjectDataSourceRegistry registry;

    private HttpHeaders service;

    @BeforeEach
    void setUpTable() {
        createDataTable("blobs", List.of(col("payload", "text", null, null), col("tag", "varchar", 32, null)));
        service = headers(fixture.secretKey(), null);
    }

    @AfterEach
    void closeProjectPool() {
        registry.blockAndDrain(fixture.project().getProjectRef());
    }

    private String blobsUrl(String queryString) {
        return baseUrl() + "/blobs" + (queryString == null ? "" : "?" + queryString);
    }

    @Test
    void chunkedOversizedRequestBody413() throws Exception {
        String big = "{\"payload\":\"" + "a".repeat(1_100_000) + "\"}";

        HttpResponse<String> response = chunkedPost(blobsUrl(null), big);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(dataPlaneObjectMapper.readTree(response.body()).get("code").asText())
            .isEqualTo("PAYLOAD_TOO_LARGE");
    }

    @Test
    void trailingJsonValueRejectedBeforeWrite() {
        ResponseEntity<String> response = call(HttpMethod.POST, blobsUrl(null), service,
                "{\"payload\":\"first\",\"tag\":\"trail\"} {\"payload\":\"second\"}");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(json(call(HttpMethod.GET, blobsUrl("tag=eq.trail"), service, null))).isEmpty();
    }

    /** 未声明 Content-Length 的 BodyPublisher 强制走 controller 流式计数兜底。 */
    private HttpResponse<String> chunkedPost(String url, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Flow.Publisher<ByteBuffer> publisher = subscriber -> subscriber.onSubscribe(new Flow.Subscription() {

            private boolean completed;

            @Override
            public synchronized void request(long count) {
                if (completed || count <= 0) {
                    return;
                }
                completed = true;
                subscriber.onNext(ByteBuffer.wrap(bytes));
                subscriber.onComplete();
            }

            @Override
            public synchronized void cancel() {
                completed = true;
            }

        });
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .header("apikey", fixture.secretKey())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.fromPublisher(publisher))
            .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void oversizedGetResponse413AndPermitsRestored() {
        for (int i = 0; i < 5; i++) {
            call(HttpMethod.POST, blobsUrl(null), service,
                    "{\"payload\":\"" + "x".repeat(2000) + "\",\"tag\":\"t" + i + "\"}");
        }

        ResponseEntity<String> response = call(HttpMethod.GET, blobsUrl(null), service, null);

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(permits.availablePermits()).isEqualTo(8);
    }

    @Test
    void oversizedPostRepresentationRollsBack() {
        HttpHeaders representation = new HttpHeaders();
        representation.putAll(service);
        representation.set("Prefer", "return=representation");
        String bigRow = "{\"payload\":\"" + "y".repeat(5000) + "\",\"tag\":\"rollback\"}";

        ResponseEntity<String> response = call(HttpMethod.POST, blobsUrl(null), representation, bigRow);

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        JsonNode rows = json(call(HttpMethod.GET, blobsUrl("tag=eq.rollback&select=tag"), service, null));
        assertThat(rows).isEmpty();
    }

    @Test
    void oversizedPatchRepresentationRollsBack() {
        call(HttpMethod.POST, blobsUrl(null), service, "{\"payload\":\"small\",\"tag\":\"p\"}");
        HttpHeaders representation = new HttpHeaders();
        representation.putAll(service);
        representation.set("Prefer", "return=representation");

        ResponseEntity<String> response = call(HttpMethod.PATCH, blobsUrl("tag=eq.p"), representation,
                "{\"payload\":\"" + "z".repeat(5000) + "\"}");

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        JsonNode rows = json(call(HttpMethod.GET, blobsUrl("tag=eq.p&select=payload,tag"), service, null));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("payload").asText()).isEqualTo("small");
    }

    @Test
    void semaphoreExhaustionReturns429NotBlocking() throws Exception {
        int expectedPermits = 8;
        assertThat(permits.availablePermits()).isEqualTo(expectedPermits);
        boolean acquired = permits.tryAcquire(expectedPermits, 5, TimeUnit.SECONDS);
        try {
            assertThat(acquired).as("应在 5 秒内取得全部响应许可").isTrue();
            ResponseEntity<String> response = call(HttpMethod.GET, blobsUrl("select=tag"), service, null);
            assertThat(response.getStatusCode().value()).isEqualTo(429);
        }
        finally {
            if (acquired) {
                permits.release(expectedPermits);
            }
        }
        call(HttpMethod.GET, blobsUrl("select=tag"), service, null);
        assertThat(permits.availablePermits()).isEqualTo(expectedPermits);
    }

    @Test
    void mdlBarrierBlocksConcurrentAlterUntilCommit() throws Exception {
        com.aiwork.baas.entity.BaasProject project = fixture.project();
        String projectUrl = MYSQL.getJdbcUrl().replace("/ai_work_baas", "/" + project.getDbName());
        try (java.sql.Connection holder = java.sql.DriverManager.getConnection(projectUrl, MYSQL_USERNAME,
                MYSQL_PASSWORD); java.sql.Statement statement = holder.createStatement()) {
            CompletableFuture<Object> alter;
            statement.execute("LOCK TABLES `blobs` READ");
            try {
                alter = CompletableFuture
                    .supplyAsync(() -> tableService.alterTable(project, "blobs",
                            new com.aiwork.baas.controller.dto.TableAlterDTO(UUID.randomUUID().toString(), null,
                                    null, null,
                                    List.of(new com.aiwork.baas.controller.dto.ColumnDefinitionDTO("extra", "int",
                                            null, null, true, null, false, false, null)),
                                    null, null, null)));
                awaitMdlWait(project.getDbName());
                assertThat(alter.isDone()).as("ALTER 应阻塞在 MDL 上").isFalse();
            }
            finally {
                statement.execute("UNLOCK TABLES");
            }
            alter.get(2, TimeUnit.MINUTES);
        }

        ResponseEntity<String> response = call(HttpMethod.GET, blobsUrl("select=extra"), service, null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void mdlAcquireTimeoutMapsToSanitized500() throws Exception {
        String projectUrl = MYSQL.getJdbcUrl().replace("/ai_work_baas", "/" + fixture.project().getDbName());
        try (java.sql.Connection holder = java.sql.DriverManager.getConnection(projectUrl, MYSQL_USERNAME,
                MYSQL_PASSWORD); java.sql.Statement statement = holder.createStatement()) {
            statement.execute("LOCK TABLES `blobs` WRITE");

            ResponseEntity<String> response = call(HttpMethod.GET, blobsUrl("select=tag"), service, null);

            assertThat(response.getStatusCode().value()).isEqualTo(500);
            assertThat(json(response).get("message").asText()).isEqualTo("SQL 执行超时");
        }
    }

    @Test
    void staleRequestRebuildsAgainstMetadataAfterAlterCompletes() throws Exception {
        CountDownLatch fastReadReturned = new CountDownLatch(1);
        CountDownLatch resumeRequest = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (calls.incrementAndGet() == 1) {
                fastReadReturned.countDown();
                if (!resumeRequest.await(30, TimeUnit.SECONDS)) {
                    throw new AssertionError("数据请求未按时恢复");
                }
            }
            return result;
        }).when(metadataService).loadActive(fixture.project().getId(), "blobs");

        CompletableFuture<ResponseEntity<String>> request = CompletableFuture
            .supplyAsync(() -> call(HttpMethod.GET, blobsUrl("select=tag"), service, null));
        assertThat(fastReadReturned.await(30, TimeUnit.SECONDS)).isTrue();
        tableService.alterTable(fixture.project(), "blobs",
                new com.aiwork.baas.controller.dto.TableAlterDTO(UUID.randomUUID().toString(), true, null, null,
                        null, List.of("tag"), null, null));
        resumeRequest.countDown();

        ResponseEntity<String> response = request.get(30, TimeUnit.SECONDS);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(json(response).get("message").asText()).contains("列");
    }

    @Test
    void alterStartingAfterMdlAcquisitionIsBlockedByBarrierReload() throws Exception {
        CountDownLatch barrierReloadReached = new CountDownLatch(1);
        CountDownLatch resumeReload = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            if (calls.incrementAndGet() == 2) {
                barrierReloadReached.countDown();
                if (!resumeReload.await(30, TimeUnit.SECONDS)) {
                    throw new AssertionError("屏障内元数据重读未按时恢复");
                }
            }
            return invocation.callRealMethod();
        }).when(metadataService).loadActive(fixture.project().getId(), "blobs");

        CompletableFuture<ResponseEntity<String>> request = CompletableFuture
            .supplyAsync(() -> call(HttpMethod.GET, blobsUrl("select=tag"), service, null));
        assertThat(barrierReloadReached.await(30, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Object> alter = CompletableFuture.supplyAsync(() -> tableService
            .alterTable(fixture.project(), "blobs",
                    new com.aiwork.baas.controller.dto.TableAlterDTO(UUID.randomUUID().toString(), null, null, null,
                            List.of(new com.aiwork.baas.controller.dto.ColumnDefinitionDTO("extra", "int", null,
                                    null, true, null, false, false, null)),
                            null, null, null)));
        awaitTableStatus("ALTERING");
        resumeReload.countDown();

        ResponseEntity<String> response = request.get(30, TimeUnit.SECONDS);
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        alter.get(2, TimeUnit.MINUTES);
    }

    private void awaitMdlWait(String dbName) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(),
                MYSQL_USERNAME, MYSQL_PASSWORD); java.sql.PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM information_schema.PROCESSLIST "
                                + "WHERE STATE = 'Waiting for table metadata lock' AND INFO LIKE ?")) {
            statement.setString(1, "%" + dbName + "%");
            while (System.nanoTime() < deadline) {
                try (java.sql.ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    if (resultSet.getInt(1) > 0) {
                        return;
                    }
                }
                Thread.sleep(25);
            }
        }
        throw new AssertionError("ALTER 未在 30 秒内进入 metadata lock 等待态");
    }

    private void awaitTableStatus(String expectedStatus) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            com.aiwork.baas.entity.BaasTable table = tableMapper
                .selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<com.aiwork.baas.entity.BaasTable>lambdaQuery()
                    .eq(com.aiwork.baas.entity.BaasTable::getProjectId, fixture.project().getId())
                    .eq(com.aiwork.baas.entity.BaasTable::getTableName, "blobs"));
            if (expectedStatus.equals(table.getStatus())) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("表未在 30 秒内进入状态 " + expectedStatus);
    }

    @Test
    void alteringTableBlocksDataRequests403() {
        com.aiwork.baas.entity.BaasTable table = tableMapper
            .selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<com.aiwork.baas.entity.BaasTable>lambdaQuery()
                .eq(com.aiwork.baas.entity.BaasTable::getProjectId, fixture.project().getId())
                .eq(com.aiwork.baas.entity.BaasTable::getTableName, "blobs"));
        tableMapper.update(null,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<com.aiwork.baas.entity.BaasTable>lambdaUpdate()
                    .eq(com.aiwork.baas.entity.BaasTable::getId, table.getId())
                    .set(com.aiwork.baas.entity.BaasTable::getStatus, "ALTERING"));

        assertThat(call(HttpMethod.GET, blobsUrl(null), service, null).getStatusCode().value()).isEqualTo(403);
    }

}
