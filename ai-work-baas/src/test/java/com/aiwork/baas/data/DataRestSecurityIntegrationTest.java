package com.aiwork.baas.data;

import com.aiwork.baas.controller.dto.AclRoleDTO;
import com.aiwork.baas.datasource.ProjectDataSourceRegistry;
import com.aiwork.baas.entity.BaasJwtKey;
import com.aiwork.baas.entity.BaasTable;
import com.aiwork.baas.entity.enums.JwtKeyStatus;
import com.aiwork.baas.entity.enums.ProjectStatus;
import com.aiwork.baas.mapper.BaasProjectMapper;
import com.aiwork.baas.mapper.BaasTableMapper;
import com.aiwork.baas.service.ProjectLifecycleService;
import com.aiwork.baas.support.DataPlaneIntegrationTestSupport;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 安全场景矩阵(spec §14 Plan C 必测:三方一致性、身份三态、ACL×角色、owner 逐格、表/项目状态阻断、紧急轮换)。
 *
 * @author ai-work
 * @date 2026/07/21
 */
@TestPropertySource(properties = "security.oauth2.client.skip-resolve-urls=/data/**")
class DataRestSecurityIntegrationTest extends DataPlaneIntegrationTestSupport {

    @Autowired
    private BaasTableMapper tableMapper;

    @Autowired
    private BaasProjectMapper projectMapper;

    @Autowired
    private ProjectDataSourceRegistry registry;

    @MockitoSpyBean
    private OpaqueTokenIntrospector platformIntrospector;

    @BeforeEach
    void setUpTable() {
        createDataTable("notes", List.of(col("body", "varchar", 128, null), col("owner_id", "bigint", null, null)));
    }

    @AfterEach
    void closeProjectPool() {
        registry.blockAndDrain(fixture.project().getProjectRef());
    }

    private String notesUrl(String queryString) {
        return baseUrl() + "/notes" + (queryString == null ? "" : "?" + queryString);
    }

    @Test
    void missingOrInvalidApiKey401() {
        assertThat(call(HttpMethod.GET, notesUrl(null), headers(null, null), null).getStatusCode().value())
            .isEqualTo(401);
        assertThat(call(HttpMethod.GET, notesUrl(null), headers("pub_bogus", null), null).getStatusCode().value())
            .isEqualTo(401);
    }

    @Test
    void otherProjectsKeyRejected401() {
        ProjectLifecycleService.CreatedProject other = lifecycleService
            .createProject("dataplane-other-" + UUID.randomUUID().toString().substring(0, 8), 1L);

        ResponseEntity<String> response = call(HttpMethod.GET, notesUrl(null),
                headers(other.publishableKey(), null), null);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void secretKeyWithJwtRejected401() {
        ResponseEntity<String> response = call(HttpMethod.GET, notesUrl(null),
                headers(fixture.secretKey(), mintJwt(1L, null)), null);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void crossProjectJwtRejected401() {
        String foreignJwt = mintJwt(1L, claims -> {
            claims.issuer("baas/otherref");
            claims.audience("otherref");
        });

        assertThat(call(HttpMethod.GET, notesUrl(null), headers(fixture.publishableKey(), foreignJwt), null)
            .getStatusCode()
            .value()).isEqualTo(401);
    }

    @Test
    void projectBearerBypassesPlatformIntrospection() {
        openAcl("notes", allClosed(), allOpen(), null);

        ResponseEntity<String> response = call(HttpMethod.GET, notesUrl(null),
                headers(fixture.publishableKey(), mintJwt(1L, null)), null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verifyNoInteractions(platformIntrospector);
    }

    @Test
    void defaultAclDeniesAnonAndAuthenticatedButNotServiceRole() {
        HttpHeaders anon = headers(fixture.publishableKey(), null);
        HttpHeaders authed = headers(fixture.publishableKey(), mintJwt(1L, null));

        assertThat(call(HttpMethod.GET, notesUrl(null), anon, null).getStatusCode().value()).isEqualTo(403);
        assertThat(call(HttpMethod.GET, notesUrl(null), authed, null).getStatusCode().value()).isEqualTo(403);
        assertThat(call(HttpMethod.GET, notesUrl(null), headers(fixture.secretKey(), null), null).getStatusCode()
            .value()).isEqualTo(200);
    }

    @Test
    void aclPerOperationIndependent() {
        openAcl("notes", new AclRoleDTO(true, false, false, false), allOpen(), null);
        HttpHeaders anon = headers(fixture.publishableKey(), null);

        assertThat(call(HttpMethod.GET, notesUrl(null), anon, null).getStatusCode().value()).isEqualTo(200);
        assertThat(call(HttpMethod.POST, notesUrl(null), anon, "{\"body\":\"x\"}").getStatusCode().value())
            .isEqualTo(403);
        assertThat(call(HttpMethod.PATCH, notesUrl("body=eq.x"), anon, "{\"body\":\"y\"}").getStatusCode().value())
            .isEqualTo(403);
        assertThat(call(HttpMethod.DELETE, notesUrl("body=eq.x"), anon, null).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void representationRequiresSelectAndFailsBeforeWrite() {
        openAcl("notes", new AclRoleDTO(false, true, true, false), allClosed(), null);
        HttpHeaders anonRepresentation = headers(fixture.publishableKey(), null);
        anonRepresentation.set("Prefer", "return=representation");

        ResponseEntity<String> post = call(HttpMethod.POST, notesUrl(null), anonRepresentation,
                "{\"body\":\"leak\"}");
        assertThat(post.getStatusCode().value()).isEqualTo(403);

        JsonNode rows = json(call(HttpMethod.GET, notesUrl(null), headers(fixture.secretKey(), null), null));
        assertThat(rows).isEmpty();

        call(HttpMethod.POST, notesUrl(null), headers(fixture.secretKey(), null), "{\"body\":\"orig\"}");
        ResponseEntity<String> patch = call(HttpMethod.PATCH, notesUrl("body=eq.orig"), anonRepresentation,
                "{\"body\":\"changed\"}");
        assertThat(patch.getStatusCode().value()).isEqualTo(403);
        JsonNode after = json(call(HttpMethod.GET, notesUrl("body=eq.orig"), headers(fixture.secretKey(), null),
                null));
        assertThat(after).hasSize(1);
    }

    @Test
    void ownerPolicyGrid() {
        openAcl("notes", allOpen(), allOpen(), "owner_id");
        HttpHeaders anon = headers(fixture.publishableKey(), null);
        HttpHeaders user7 = headers(fixture.publishableKey(), mintJwt(7L, null));
        HttpHeaders user8 = headers(fixture.publishableKey(), mintJwt(8L, null));
        HttpHeaders service = headers(fixture.secretKey(), null);

        assertThat(call(HttpMethod.POST, notesUrl(null), user7, "{\"body\":\"u7\"}").getStatusCode().value())
            .isEqualTo(201);
        assertThat(call(HttpMethod.POST, notesUrl(null), anon, "{\"body\":\"free\"}").getStatusCode().value())
            .isEqualTo(201);
        assertThat(call(HttpMethod.POST, notesUrl(null), service, "{\"body\":\"s8\",\"owner_id\":8}").getStatusCode()
            .value()).isEqualTo(201);

        assertThat(call(HttpMethod.POST, notesUrl(null), user7, "{\"body\":\"x\",\"owner_id\":9}").getStatusCode()
            .value()).isEqualTo(400);
        assertThat(call(HttpMethod.PATCH, notesUrl("body=eq.u7"), user7, "{\"owner_id\":9}").getStatusCode().value())
            .isEqualTo(400);
        assertThat(call(HttpMethod.POST, notesUrl(null), anon, "{\"body\":\"x\",\"owner_id\":9}").getStatusCode()
            .value()).isEqualTo(400);

        assertThat(json(call(HttpMethod.GET, notesUrl(null), user7, null))).hasSize(1);
        assertThat(json(call(HttpMethod.GET, notesUrl(null), user8, null))).hasSize(1);
        assertThat(json(call(HttpMethod.GET, notesUrl(null), anon, null))).hasSize(1);
        assertThat(json(call(HttpMethod.GET, notesUrl(null), service, null))).hasSize(3);

        assertThat(json(call(HttpMethod.PATCH, notesUrl("body=eq.s8"), user7, "{\"body\":\"hijack\"}"))
            .get("count")
            .asInt()).isZero();
        assertThat(json(call(HttpMethod.DELETE, notesUrl("body=eq.s8"), anon, null)).get("count").asInt()).isZero();
        assertThat(json(call(HttpMethod.DELETE, notesUrl("body=eq.s8"), service, null)).get("count").asInt())
            .isEqualTo(1);
    }

    @Test
    void blockedTableStatesMapTo403And404() {
        HttpHeaders service = headers(fixture.secretKey(), null);
        BaasTable table = tableMapper.selectOne(Wrappers.<BaasTable>lambdaQuery()
            .eq(BaasTable::getProjectId, fixture.project().getId())
            .eq(BaasTable::getTableName, "notes"));

        for (String blocked : List.of("CREATING", "ALTERING", "FAILED", "CONFLICT")) {
            tableMapper.update(null, Wrappers.<BaasTable>lambdaUpdate()
                .eq(BaasTable::getId, table.getId())
                .set(BaasTable::getStatus, blocked));
            ResponseEntity<String> response = call(HttpMethod.GET, notesUrl(null), service, null);
            assertThat(response.getStatusCode().value()).as(blocked).isEqualTo(403);
            assertThat(json(response).get("hint").isTextual()).isTrue();
        }
        tableMapper.update(null, Wrappers.<BaasTable>lambdaUpdate()
            .eq(BaasTable::getId, table.getId())
            .set(BaasTable::getStatus, "DELETED"));
        assertThat(call(HttpMethod.GET, notesUrl(null), service, null).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void nonActiveProject403() {
        projectMapper.update(null, Wrappers.<com.aiwork.baas.entity.BaasProject>lambdaUpdate()
            .eq(com.aiwork.baas.entity.BaasProject::getId, fixture.project().getId())
            .set(com.aiwork.baas.entity.BaasProject::getStatus, ProjectStatus.MIGRATING));

        assertThat(call(HttpMethod.GET, notesUrl(null), headers(fixture.secretKey(), null), null).getStatusCode()
            .value()).isEqualTo(403);
    }

    @Test
    void revokedKidRejectedImmediately() {
        String jwt = mintJwt(1L, null);
        openAcl("notes", allClosed(), allOpen(), null);
        assertThat(call(HttpMethod.GET, notesUrl(null), headers(fixture.publishableKey(), jwt), null).getStatusCode()
            .value()).isEqualTo(200);

        jwtKeyMapper.update(null, Wrappers.<BaasJwtKey>lambdaUpdate()
            .eq(BaasJwtKey::getProjectId, fixture.project().getId())
            .set(BaasJwtKey::getStatus, JwtKeyStatus.REVOKED));

        assertThat(call(HttpMethod.GET, notesUrl(null), headers(fixture.publishableKey(), jwt), null).getStatusCode()
            .value()).isEqualTo(401);
    }

}
