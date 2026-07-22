package com.aiwork.baas.data.auth;

import com.aiwork.baas.data.config.DataPlaneProperties;
import com.aiwork.baas.data.context.DataRequestContext;
import com.aiwork.baas.data.error.DataErrorWriter;
import com.aiwork.baas.entity.BaasApiKey;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.enums.KeyType;
import com.aiwork.baas.entity.enums.ProjectStatus;
import com.aiwork.baas.mapper.BaasApiKeyMapper;
import com.aiwork.baas.mapper.BaasProjectMapper;
import com.aiwork.baas.security.key.ApiKeyGenerator;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API Key 鉴权过滤器 last_used_time 节流与 best-effort 行为测试。
 *
 * @author ai-work
 * @date 2026/07/22
 */
class ApiKeyAuthFilterTests {

    private static final String API_KEY = "sec_filter_test";

    private static final String PROJECT_REF = "filtertestprojectref";

    private BaasApiKeyMapper apiKeyMapper;

    private BaasProjectMapper projectMapper;

    private final AtomicInteger touchUpdates = new AtomicInteger();

    private final AtomicBoolean failTouch = new AtomicBoolean();

    private DataPlaneProperties properties;

    private ApiKeyAuthFilter filter;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                BaasApiKey.class);
        ApiKeyGenerator keyGenerator = new ApiKeyGenerator();
        BaasApiKey apiKey = new BaasApiKey();
        apiKey.setId(11L);
        apiKey.setProjectId(7L);
        apiKey.setKeyType(KeyType.SECRET);
        apiKey.setKeyHash(keyGenerator.sha256Hex(API_KEY));
        apiKey.setStatus("ACTIVE");
        BaasProject project = new BaasProject();
        project.setId(7L);
        project.setProjectRef(PROJECT_REF);
        project.setStatus(ProjectStatus.ACTIVE);

        apiKeyMapper = mapperProxy(BaasApiKeyMapper.class, (methodName, args) -> switch (methodName) {
            case "selectOne" -> apiKey;
            case "update" -> {
                touchUpdates.incrementAndGet();
                if (failTouch.get()) {
                    throw new IllegalStateException("metadata store unavailable");
                }
                yield 1;
            }
            default -> throw new AssertionError("unexpected api key mapper call: " + methodName);
        });
        projectMapper = mapperProxy(BaasProjectMapper.class, (methodName, args) -> switch (methodName) {
            case "selectById" -> project;
            default -> throw new AssertionError("unexpected project mapper call: " + methodName);
        });
        properties = new DataPlaneProperties();
        filter = new ApiKeyAuthFilter(apiKeyMapper, projectMapper, keyGenerator, null,
                new DataErrorWriter(JsonMapper.builder().build()), properties);
    }

    @Test
    void concurrentRequestsTouchOnceWithinWindowAndReplaceAfterWindow() throws Exception {
        int requestCount = 8;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger chainCalls = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        List<Future<?>> requests = new ArrayList<>();
        try {
            for (int index = 0; index < requestCount; index++) {
                requests.add(executor.submit(() -> {
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    invokeFilter(chainCalls);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> request : requests) {
                request.get(10, TimeUnit.SECONDS);
            }
        }
        finally {
            executor.shutdownNow();
        }

        assertThat(chainCalls).hasValue(requestCount);
        assertThat(touchUpdates).hasValue(1);

        properties.setKeyTouchThrottleSeconds(0);
        invokeFilter(chainCalls);

        assertThat(chainCalls).hasValue(requestCount + 1);
        assertThat(touchUpdates).hasValue(2);
    }

    @Test
    void touchFailureDoesNotRejectAuthenticatedRequest() throws Exception {
        failTouch.set(true);
        AtomicInteger chainCalls = new AtomicInteger();

        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (servletRequest, servletResponse) -> chainCalls.incrementAndGet());

        assertThat(chainCalls).hasValue(1);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(request.getAttribute(DataRequestContext.ATTRIBUTE)).isInstanceOf(DataRequestContext.class);
        assertThat(touchUpdates).hasValue(1);
    }

    private void invokeFilter(AtomicInteger chainCalls) throws Exception {
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (servletRequest, servletResponse) -> chainCalls.incrementAndGet());
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(request.getAttribute(DataRequestContext.ATTRIBUTE)).isInstanceOf(DataRequestContext.class);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/data/" + PROJECT_REF + "/rest/v1/orders");
        request.addHeader("apikey", API_KEY);
        return request;
    }

    @FunctionalInterface
    private interface MapperInvocation {

        Object invoke(String methodName, Object[] args);

    }

    @SuppressWarnings("unchecked")
    private static <T> T mapperProxy(Class<T> mapperType, MapperInvocation invocation) {
        return (T)Proxy.newProxyInstance(mapperType.getClassLoader(), new Class<?>[] { mapperType },
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> mapperType.getSimpleName() + "TestDouble";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> throw new AssertionError("unexpected Object method: " + method.getName());
                        };
                    }
                    return invocation.invoke(method.getName(), args);
                });
    }

}
