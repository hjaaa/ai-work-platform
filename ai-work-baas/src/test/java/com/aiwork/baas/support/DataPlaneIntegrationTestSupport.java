package com.aiwork.baas.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.aiwork.baas.DataPlaneTestApplication;
import com.aiwork.baas.controller.dto.AclConfigDTO;
import com.aiwork.baas.controller.dto.AclPutDTO;
import com.aiwork.baas.controller.dto.AclRoleDTO;
import com.aiwork.baas.controller.dto.ColumnDefinitionDTO;
import com.aiwork.baas.controller.dto.TableCreateDTO;
import com.aiwork.baas.entity.BaasJwtKey;
import com.aiwork.baas.entity.enums.JwtKeyStatus;
import com.aiwork.baas.mapper.BaasJwtKeyMapper;
import com.aiwork.baas.security.crypto.BaasCryptoService;
import com.aiwork.baas.service.AclConfigService;
import com.aiwork.baas.service.ProjectLifecycleService;
import com.aiwork.baas.service.TableManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;

import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 数据面集成测试基座:真实 HTTP 端口 + 造数与签 JWT 助手。
 *
 * @author ai-work
 * @date 2026/07/21
 */
@SpringBootTest(classes = DataPlaneTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = { "spring.config.import=", "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false" })
public abstract class DataPlaneIntegrationTestSupport extends PlanBContainerSupport {

    @LocalServerPort
    protected int port;

    @Autowired
    protected ProjectLifecycleService lifecycleService;

    @Autowired
    protected TableManagementService tableService;

    @Autowired
    protected AclConfigService aclService;

    @Autowired
    protected BaasJwtKeyMapper jwtKeyMapper;

    @Autowired
    protected BaasCryptoService cryptoService;

    @Autowired
    @Qualifier("dataPlaneObjectMapper")
    protected ObjectMapper dataPlaneObjectMapper;

    protected ProjectLifecycleService.CreatedProject fixture;

    /** 不抛 4xx/5xx 异常、URI 不做模板展开的原生客户端。 */
    protected RestTemplate rest;

    @DynamicPropertySource
    static void registerDataPlaneProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", () -> MYSQL_USERNAME);
        registry.add("spring.datasource.password", () -> MYSQL_PASSWORD);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
        registry.add("baas.provisioner.url", () -> MYSQL.getJdbcUrl().replace("/ai_work_baas", "/mysql"));
        registry.add("baas.provisioner.username", () -> MYSQL_USERNAME);
        registry.add("baas.provisioner.password", () -> MYSQL_PASSWORD);
        registry.add("baas.project-db.host", MYSQL::getHost);
        registry.add("baas.project-db.port", () -> MYSQL.getMappedPort(3306));
        registry.add("server.servlet.context-path", () -> "");
    }

    @BeforeEach
    void createDataPlaneFixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        fixture = lifecycleService.createProject("dataplane-" + suffix, 1L);
        DefaultUriBuilderFactory uriFactory = new DefaultUriBuilderFactory();
        uriFactory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.NONE);
        rest = new RestTemplate();
        // 默认 SimpleClientHttpRequestFactory 不支持 PATCH,换 JDK HttpClient 实现
        rest.setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        rest.setUriTemplateHandler(uriFactory);
        rest.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                return false;
            }
        });
    }

    protected String baseUrl() {
        return "http://localhost:" + port + "/data/" + fixture.project().getProjectRef() + "/rest/v1";
    }

    protected HttpHeaders headers(String apiKey, String jwt) {
        HttpHeaders headers = new HttpHeaders();
        if (apiKey != null) {
            headers.set("apikey", apiKey);
        }
        if (jwt != null) {
            headers.setBearerAuth(jwt);
        }
        headers.set("Content-Type", "application/json");
        return headers;
    }

    protected ResponseEntity<String> call(HttpMethod method, String url, HttpHeaders headers, String body) {
        return rest.exchange(url, method, new HttpEntity<>(body, headers), String.class);
    }

    protected JsonNode json(ResponseEntity<String> response) {
        try {
            return dataPlaneObjectMapper.readTree(response.getBody());
        }
        catch (Exception exception) {
            throw new IllegalStateException("响应不是 JSON: " + response.getBody(), exception);
        }
    }

    /** 建表(列定义列表不含 id,id 由服务端自动生成)。 */
    protected void createDataTable(String tableName, List<ColumnDefinitionDTO> columns) {
        tableService.createTable(fixture.project(),
                new TableCreateDTO(UUID.randomUUID().toString(), tableName, "数据面测试表", columns));
    }

    protected static ColumnDefinitionDTO col(String name, String dataType, Integer length, Integer scale) {
        return new ColumnDefinitionDTO(name, dataType, length, scale, true, null, false, false, null);
    }

    /** 开 ACL;ownerColumn 传 null 表示不配置 owner。 */
    protected void openAcl(String tableName, AclRoleDTO anon, AclRoleDTO authenticated, String ownerColumn) {
        aclService.putAcl(fixture.project(), tableName,
                new AclPutDTO(UUID.randomUUID().toString(), new AclConfigDTO(anon, authenticated), ownerColumn));
    }

    protected static AclRoleDTO allOpen() {
        return new AclRoleDTO(true, true, true, true);
    }

    protected static AclRoleDTO allClosed() {
        return new AclRoleDTO(false, false, false, false);
    }

    /** 读取项目当前 CURRENT JWT key 并解密出 HMAC 密钥字节。 */
    protected byte[] currentJwtSecret() {
        BaasJwtKey key = currentJwtKey();
        String base64 = cryptoService.decrypt(key.getSecretCipher(),
                fixture.project().getId() + ":jwt_secret:" + key.getKid());
        return Base64.getDecoder().decode(base64);
    }

    protected BaasJwtKey currentJwtKey() {
        return jwtKeyMapper.selectOne(Wrappers.<BaasJwtKey>lambdaQuery()
            .eq(BaasJwtKey::getProjectId, fixture.project().getId())
            .eq(BaasJwtKey::getStatus, JwtKeyStatus.CURRENT));
    }

    /** 签发规范 access JWT;customizer 可覆盖任意 claim 用于负面用例。 */
    protected String mintJwt(long userId, Consumer<JWTClaimsSet.Builder> customizer) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer("baas/" + fixture.project().getProjectRef())
                .audience(fixture.project().getProjectRef())
                .subject(String.valueOf(userId))
                .claim("role", "authenticated")
                .claim("session_id", UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)));
            if (customizer != null) {
                customizer.accept(claims);
            }
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(currentJwtKey().getKid()).build(),
                    claims.build());
            jwt.sign(new MACSigner(currentJwtSecret()));
            return jwt.serialize();
        }
        catch (Exception exception) {
            throw new IllegalStateException("mint jwt failed", exception);
        }
    }

}
