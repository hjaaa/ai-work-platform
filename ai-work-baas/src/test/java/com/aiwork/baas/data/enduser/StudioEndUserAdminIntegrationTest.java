package com.aiwork.baas.data.enduser;

import com.aiwork.baas.entity.BaasAuditLog;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.enums.ProjectStatus;
import com.aiwork.baas.exception.DdlConflictException;
import com.aiwork.baas.mapper.BaasAuditLogMapper;
import com.aiwork.baas.mapper.BaasProjectMapper;
import com.aiwork.baas.service.EndUserAdminService;
import com.aiwork.baas.support.DataPlaneIntegrationTestSupport;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Studio 终端用户管理(spec §7.3/§14):软删闭环、幂等、审计 best-effort、状态与版本门禁。
 */
class StudioEndUserAdminIntegrationTest extends DataPlaneIntegrationTestSupport {

    @Autowired
    EndUserAdminService adminService;

    @Autowired
    BaasProjectMapper baasProjectMapper;

    @MockitoSpyBean
    BaasAuditLogMapper auditLogMapper;

    private String authUrl(String path) {
        return "http://localhost:" + port + "/data/" + fixture.project().getProjectRef() + "/auth/v1" + path;
    }

    private JsonNode signup(String email) {
        return json(call(HttpMethod.POST, authUrl("/signup"), headers(fixture.publishableKey(), null),
                "{\"email\":\"" + email + "\",\"password\":\"password-ok\"}"));
    }

    private int loginStatus(String email) {
        return call(HttpMethod.POST, authUrl("/token?grant_type=password"),
                headers(fixture.publishableKey(), null),
                "{\"email\":\"" + email + "\",\"password\":\"password-ok\"}").getStatusCode().value();
    }

    @Test
    void softDeleteRestoreRoundTrip() {
        JsonNode session = signup("victim@example.com");
        long userId = session.get("user").get("id").longValue();
        String refreshToken = session.get("refresh_token").textValue();

        adminService.softDelete(fixture.project(), userId, 1L);
        // 软删后:login 统一 401、会话即时撤销(refresh 401)、同邮箱 signup 409
        assertThat(loginStatus("victim@example.com")).isEqualTo(401);
        assertThat(call(HttpMethod.POST, authUrl("/token?grant_type=refresh_token"),
                headers(fixture.publishableKey(), null),
                "{\"refresh_token\":\"" + refreshToken + "\"}").getStatusCode().value()).isEqualTo(401);
        assertThat(call(HttpMethod.POST, authUrl("/signup"), headers(fixture.publishableKey(), null),
                "{\"email\":\"victim@example.com\",\"password\":\"password-ok\"}").getStatusCode().value())
            .isEqualTo(409);
        // 幂等:重复软删成功
        assertThatCode(() -> adminService.softDelete(fixture.project(), userId, 1L)).doesNotThrowAnyException();
        // 列表含软删状态
        EndUserAdminService.UserPage page = adminService.list(fixture.project(), 1, 20);
        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.records().get(0).deletedAt()).isNotNull();

        adminService.restore(fixture.project(), userId, 1L);
        // 恢复后可重新登录,旧会话不复活
        assertThat(loginStatus("victim@example.com")).isEqualTo(200);
        assertThat(call(HttpMethod.POST, authUrl("/token?grant_type=refresh_token"),
                headers(fixture.publishableKey(), null),
                "{\"refresh_token\":\"" + refreshToken + "\"}").getStatusCode().value()).isEqualTo(401);
        // 幂等:未软删 restore 成功
        assertThatCode(() -> adminService.restore(fixture.project(), userId, 1L)).doesNotThrowAnyException();
        // 软删与恢复均入审计
        assertThat(auditLogMapper.selectCount(Wrappers.<BaasAuditLog>lambdaQuery()
            .eq(BaasAuditLog::getProjectId, fixture.project().getId())
            .eq(BaasAuditLog::getAction, "END_USER_SOFT_DELETE"))).isEqualTo(1L);
        assertThat(auditLogMapper.selectCount(Wrappers.<BaasAuditLog>lambdaQuery()
            .eq(BaasAuditLog::getProjectId, fixture.project().getId())
            .eq(BaasAuditLog::getAction, "END_USER_RESTORE"))).isEqualTo(1L);
    }

    /** 审计 best-effort(spec §7.3/§14):平台库审计写入失败,业务已提交且成功返回,error 日志留痕。 */
    @Test
    void auditFailureDoesNotRollBackBusiness() {
        JsonNode session = signup("bestefrt@example.com");
        long userId = session.get("user").get("id").longValue();
        Mockito.doThrow(new RuntimeException("audit down")).when(auditLogMapper)
            .insert(Mockito.argThat((BaasAuditLog auditLog) -> auditLog != null
                    && "END_USER_SOFT_DELETE".equals(auditLog.getAction())));

        assertThatCode(() -> adminService.softDelete(fixture.project(), userId, 1L)).doesNotThrowAnyException();
        // 项目库变更已提交:login 已被拒
        assertThat(loginStatus("bestefrt@example.com")).isEqualTo(401);
        Mockito.reset(auditLogMapper);
    }

    @Test
    void userNotFoundIs404Semantics() {
        assertThatThrownBy(() -> adminService.softDelete(fixture.project(), 999999L, 1L))
            .isInstanceOf(com.aiwork.baas.exception.EndUserNotFoundException.class);
    }

    /** 状态与版本门禁(spec §9.1/§14):MIGRATING/FAILED/DELETING 与非 v3 逐一阻断。 */
    @Test
    void gateBlocksNonActiveAndStaleVersion() {
        JsonNode session = signup("gate@example.com");
        long userId = session.get("user").get("id").longValue();
        for (ProjectStatus status : new ProjectStatus[] { ProjectStatus.MIGRATING, ProjectStatus.FAILED,
                ProjectStatus.DELETING }) {
            setProject(status, 3);
            BaasProject stale = baasProjectMapper.selectById(fixture.project().getId());
            assertThatThrownBy(() -> adminService.list(stale, 1, 20)).isInstanceOf(DdlConflictException.class);
            assertThatThrownBy(() -> adminService.softDelete(stale, userId, 1L))
                .isInstanceOf(DdlConflictException.class);
            assertThatThrownBy(() -> adminService.restore(stale, userId, 1L))
                .isInstanceOf(DdlConflictException.class);
        }
        setProject(ProjectStatus.ACTIVE, 0);
        BaasProject staleVersion = baasProjectMapper.selectById(fixture.project().getId());
        assertThatThrownBy(() -> adminService.list(staleVersion, 1, 20)).isInstanceOf(DdlConflictException.class);
        setProject(ProjectStatus.ACTIVE, 3);
    }

    private void setProject(ProjectStatus status, int version) {
        baasProjectMapper.update(null, Wrappers.<BaasProject>lambdaUpdate()
            .eq(BaasProject::getId, fixture.project().getId())
            .set(BaasProject::getStatus, status)
            .set(BaasProject::getSystemTableVersion, version));
    }

}
