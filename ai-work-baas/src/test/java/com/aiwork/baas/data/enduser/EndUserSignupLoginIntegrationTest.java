package com.aiwork.baas.data.enduser;

import com.aiwork.baas.support.DataPlaneIntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * signup/login 契约(spec §7.2/§7.6/§12.2/§14):响应同构、统一 401 文案、
 * 软删邮箱占用 409、字节边界、限速 429 + Retry-After、登录成功清计数。
 */
class EndUserSignupLoginIntegrationTest extends DataPlaneIntegrationTestSupport {

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    com.aiwork.baas.mapper.BaasProjectMapper baasProjectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    com.aiwork.baas.security.key.ApiKeyGenerator keyGenerator;

    private String authUrl(String path) {
        return "http://localhost:" + port + "/data/" + fixture.project().getProjectRef() + "/auth/v1" + path;
    }

    private ResponseEntity<String> signup(String email, String password) {
        return call(HttpMethod.POST, authUrl("/signup"), headers(fixture.publishableKey(), null),
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    private ResponseEntity<String> login(String email, String password) {
        return call(HttpMethod.POST, authUrl("/token?grant_type=password"),
                headers(fixture.publishableKey(), null),
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    @Test
    void signupIsLoginShapedAndNormalizesEmail() {
        ResponseEntity<String> response = signup("  Alice@Example.COM ", "correct-horse");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("access_token").isTextual()).isTrue();
        assertThat(body.get("token_type").textValue()).isEqualTo("bearer");
        assertThat(body.get("expires_in").longValue()).isEqualTo(3600L);
        assertThat(body.get("refresh_token").textValue()).startsWith("rt_");
        assertThat(body.get("user").get("email").textValue()).isEqualTo("alice@example.com");
        assertThat(body.get("user").get("id").isNumber()).isTrue();
        assertThat(body.get("user").get("createTime").textValue())
            .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
        // 签发的 access JWT 可直接用于数据面(全链路详见 Task 15)
        ResponseEntity<String> loginResponse = login("alice@example.com", "correct-horse");
        assertThat(loginResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(json(loginResponse).get("user").get("id"))
            .isEqualTo(body.get("user").get("id"));
    }

    @Test
    void duplicateEmailIs409() {
        signup("dup@example.com", "password-1");
        assertThat(signup("dup@example.com", "password-2").getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void loginFailuresAreUniform401() {
        signup("known@example.com", "password-1");
        // 密码错误与邮箱不存在:同一 401 文案(不泄露注册状态)
        ResponseEntity<String> wrongPassword = login("known@example.com", "bad-password");
        ResponseEntity<String> unknownEmail = login("ghost@example.com", "bad-password");
        assertThat(wrongPassword.getStatusCode().value()).isEqualTo(401);
        assertThat(unknownEmail.getStatusCode().value()).isEqualTo(401);
        assertThat(json(wrongPassword).get("message")).isEqualTo(json(unknownEmail).get("message"));
    }

    @Test
    void passwordByteBounds() {
        assertThat(signup("short@example.com", "1234567").getStatusCode().value()).isEqualTo(400);
        // 73 字节(全 ASCII)→ 400;72 字节通过
        assertThat(signup("long@example.com", "a".repeat(73)).getStatusCode().value()).isEqualTo(400);
        assertThat(signup("edge@example.com", "a".repeat(72)).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void emailDimensionRateLimitAndClearOnSuccess() {
        signup("victim@example.com", "password-ok");
        for (int i = 0; i < 5; i++) {
            assertThat(login("victim@example.com", "wrong-" + i).getStatusCode().value()).isEqualTo(401);
        }
        ResponseEntity<String> blocked = login("victim@example.com", "password-ok");
        assertThat(blocked.getStatusCode().value()).isEqualTo(429);
        assertThat(blocked.getHeaders().getFirst("Retry-After")).isNotBlank();
        // 手动清计数模拟窗口过期后,登录成功并再次清零
        redisTemplate.delete(AuthRateLimiter.emailKey("login", fixture.project().getId(),
                keyGenerator.sha256Hex("victim@example.com")));
        assertThat(login("victim@example.com", "password-ok").getStatusCode().value()).isEqualTo(200);
        assertThat(login("victim@example.com", "wrong-again").getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void signupIpRateLimit() {
        for (int i = 0; i < 10; i++) {
            assertThat(signup("bulk" + i + "@example.com", "password-ok").getStatusCode().value())
                .isEqualTo(200);
        }
        ResponseEntity<String> blocked = signup("bulk10@example.com", "password-ok");
        assertThat(blocked.getStatusCode().value()).isEqualTo(429);
        assertThat(blocked.getHeaders().getFirst("Retry-After")).isNotBlank();
    }

    /** Redis 键不落邮箱原文(spec §12.2/§14):键空间内不得出现明文邮箱。 */
    @Test
    void redisKeysContainNoRawEmail() {
        signup("plainmail@example.com", "password-ok");
        login("plainmail@example.com", "wrong-password");
        java.util.Set<String> keys = redisTemplate.keys("baas:auth:rl:*");
        assertThat(keys).isNotEmpty()
            .allSatisfy(key -> assertThat(key).doesNotContain("plainmail@example.com"));
    }

    @Test
    void staleSystemTableVersionFailsClosed() {
        baasProjectMapper.update(null,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<com.aiwork.baas.entity.BaasProject>lambdaUpdate()
                    .eq(com.aiwork.baas.entity.BaasProject::getId, fixture.project().getId())
                    .set(com.aiwork.baas.entity.BaasProject::getSystemTableVersion, 0));
        ResponseEntity<String> response = signup("gated@example.com", "password-ok");
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(json(response).get("message").textValue()).contains("系统表升级未完成");
    }

    /** 清空限速计数,消除跨测试方法在同一 IP/项目下的累加污染。 */
    private void clearRateLimitKeys() {
        java.util.Set<String> keys = redisTemplate.keys("baas:auth:rl:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    /** login IP 维度独立触发(loginIpLimit=30):用 30 个不同邮箱各失败一次,邮箱维度永不触顶。 */
    @Test
    void loginIpDimensionRateLimit() {
        clearRateLimitKeys();
        for (int i = 0; i < 30; i++) {
            // 不同(未注册)邮箱各失败一次:统一 401,仅推进 IP 维度计数
            assertThat(login("ipdim" + i + "@example.com", "wrong-password").getStatusCode().value())
                .isEqualTo(401);
        }
        ResponseEntity<String> blocked = login("ipdim-final@example.com", "wrong-password");
        assertThat(blocked.getStatusCode().value()).isEqualTo(429);
        assertThat(blocked.getHeaders().getFirst("Retry-After")).isNotBlank();
    }

    /** 成功登录退还 IP 预留(§12.2):bcrypt 前预留的 IP 配额在认证成功后 DECR 归还,不占失败配额。 */
    @Test
    void successfulLoginRefundsIpReservation() {
        clearRateLimitKeys();
        signup("refund@example.com", "password-ok");
        assertThat(login("refund@example.com", "password-ok").getStatusCode().value()).isEqualTo(200);
        // 成功登录后 login IP 计数应被退还:不残留 count>0 的 login IP 键
        java.util.Set<String> ipKeys = redisTemplate
            .keys("baas:auth:rl:login:ip:" + fixture.project().getId() + ":*");
        if (ipKeys != null) {
            for (String key : ipKeys) {
                String value = redisTemplate.opsForValue().get(key);
                assertThat(value == null || Long.parseLong(value) == 0L)
                    .as("login IP 预留应已退还,实际计数=%s", value).isTrue();
            }
        }
    }

    /**
     * 并发失败登录不得穿透邮箱维度阈值(§12.2):基于原子 INCR 返回值硬闸,
     * 至多 loginEmailLimit 个失败以 401 通过,其余必被 429 拦截。
     * TOCTOU 穿透(丢弃 INCR 返回值、仅靠前置 GET)会让全部并发请求返回 401、429=0,使断言失败。
     */
    @Test
    void concurrentLoginFailuresDoNotPunchThrough() throws Exception {
        signup("race@example.com", "password-ok");
        clearRateLimitKeys();
        int concurrency = 12;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(concurrency);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(concurrency);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Integer>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return login("race@example.com", "wrong-password").getStatusCode().value();
            }));
        }
        ready.await();
        start.countDown(); // 同时放行,制造 check-then-act 竞态窗口
        int count401 = 0;
        int count429 = 0;
        for (java.util.concurrent.Future<Integer> future : futures) {
            int status = future.get();
            if (status == 401) {
                count401++;
            }
            else if (status == 429) {
                count429++;
            }
        }
        pool.shutdown();
        assertThat(count401).isLessThanOrEqualTo(5);
        assertThat(count429).isGreaterThan(0);
        assertThat(count401 + count429).isEqualTo(concurrency);
    }

    /**
     * 无 Content-Length(chunked)且超 1 MiB 的 signup body:过滤器预检失效,
     * 靠 controller 流式计数兜底返回 413,且服务层未触达 → 项目库无写入(§13)。
     */
    @Test
    void chunkedOversizedSignupBody413AndNoWrite() throws Exception {
        String big = "{\"email\":\"chunk@example.com\",\"password\":\"password-ok\",\"pad\":\""
                + "a".repeat(1_100_000) + "\"}";
        java.net.http.HttpResponse<String> response = chunkedPost(authUrl("/signup"), big);
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("PAYLOAD_TOO_LARGE");
        // 413 发生在 readBody 计数兜底,服务层未插入用户 → 该邮箱仍无法登录
        assertThat(login("chunk@example.com", "password-ok").getStatusCode().value()).isEqualTo(401);
    }

    /** 未声明 Content-Length 的 BodyPublisher 强制走 controller 流式计数兜底(§13 第二道防线)。 */
    private java.net.http.HttpResponse<String> chunkedPost(String url, String body) throws Exception {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.util.concurrent.Flow.Publisher<java.nio.ByteBuffer> publisher = subscriber ->
                subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {

                    private boolean completed;

                    @Override
                    public synchronized void request(long count) {
                        if (completed || count <= 0) {
                            return;
                        }
                        completed = true;
                        subscriber.onNext(java.nio.ByteBuffer.wrap(bytes));
                        subscriber.onComplete();
                    }

                    @Override
                    public synchronized void cancel() {
                        completed = true;
                    }

                });
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
            .header("apikey", fixture.publishableKey())
            .header("Content-Type", "application/json")
            .POST(java.net.http.HttpRequest.BodyPublishers.fromPublisher(publisher))
            .build();
        return java.net.http.HttpClient.newHttpClient().send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());
    }

}
