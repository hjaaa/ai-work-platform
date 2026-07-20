/*
 *
 *      Copyright (c) 2018-2025, lengleng All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 * Neither the name of the pig4cloud.com developer nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 * Author: lengleng (wangiegie@gmail.com)
 *
 */

package com.aiwork.baas.controller;

import com.aiwork.baas.controller.dto.ApiKeyVO;
import com.aiwork.baas.controller.dto.CreatedKeyVO;
import com.aiwork.baas.controller.dto.ReconcileTriggerDTO;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.enums.KeyType;
import com.aiwork.baas.entity.enums.ProjectStatus;
import com.aiwork.baas.exception.BaasStudioExceptionHandler;
import com.aiwork.baas.exception.ProjectNotFoundException;
import com.aiwork.baas.exception.ProjectProvisionException;
import com.aiwork.baas.security.CurrentUserProvider;
import com.aiwork.baas.service.ProjectAccessService;
import com.aiwork.baas.service.ProjectKeyService;
import com.aiwork.baas.service.ProjectLifecycleService;
import com.aiwork.baas.service.ReconcileService;
import com.aiwork.baas.service.SystemTableMigrationResult;
import com.aiwork.baas.service.SystemTableMigrationService;
import com.aiwork.common.core.jackson.AiWorkJavaTimeModule;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StudioProjectControllerTest {

    private static final String PROJECT_REF = "abcdefghijabcdefghij";

    private static final String KEY_ID = "9007199254740993";

    private ProjectLifecycleService lifecycleService;

    private ProjectAccessService accessService;

    private ProjectKeyService keyService;

    private CurrentUserProvider userProvider;

    private ReconcileService reconcileService;

    private SystemTableMigrationService migrationService;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new AiWorkJavaTimeModule());

    private MockMvc mockMvc;

    private static BaasProject project(long id, String projectRef, long ownerUserId) {
        BaasProject project = new BaasProject();
        project.setId(id);
        project.setProjectRef(projectRef);
        project.setName("demo");
        project.setStatus(ProjectStatus.ACTIVE);
        project.setOwnerUserId(ownerUserId);
        project.setAllowedOrigins("[\"https://a.example\"]");
        project.setDbName("baas_" + projectRef);
        project.setRuntimeDbUser("rt_secret");
        project.setRuntimeDbPasswordCipher("v1:k1:should-never-leak");
        project.setProvisionStep("API_KEYS");
        project.setDeleteAfter(LocalDateTime.now().plusDays(7));
        project.setCreateTime(LocalDateTime.of(2026, 7, 17, 12, 0));
        project.setUpdateTime(LocalDateTime.of(2026, 7, 17, 12, 1));
        return project;
    }

    @BeforeEach
    void setUp() {
        lifecycleService = Mockito.mock(ProjectLifecycleService.class);
        accessService = Mockito.mock(ProjectAccessService.class);
        keyService = Mockito.mock(ProjectKeyService.class);
        userProvider = Mockito.mock(CurrentUserProvider.class);
        reconcileService = Mockito.mock(ReconcileService.class);
        migrationService = Mockito.mock(SystemTableMigrationService.class);
        when(userProvider.currentUserId()).thenReturn(1L);
        mockMvc = MockMvcBuilders
            .standaloneSetup(
                    new StudioProjectController(lifecycleService, accessService, keyService, userProvider,
                            reconcileService, migrationService))
            .setControllerAdvice(new BaasStudioExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
    }

    @Test
    void listReturnsVisibleProjectsAsSafeVo() throws Exception {
        when(accessService.listVisible()).thenReturn(List.of(project(10L, PROJECT_REF, 1L)));

        mockMvc.perform(get("/studio/projects"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].projectRef").value(PROJECT_REF))
            .andExpect(jsonPath("$.data[0].allowedOrigins[0]").value("https://a.example"))
            .andExpect(jsonPath("$.data[0].id").doesNotExist())
            .andExpect(jsonPath("$.data[0].runtimeDbPasswordCipher").doesNotExist())
            .andExpect(jsonPath("$.data[0].runtimeDbUser").doesNotExist())
            .andExpect(jsonPath("$.data[0].dbName").doesNotExist())
            .andExpect(jsonPath("$.data[0].ownerUserId").doesNotExist())
            .andExpect(jsonPath("$.data[0].provisionStep").doesNotExist())
            .andExpect(jsonPath("$.data[0].deleteAfter").doesNotExist())
            .andExpect(jsonPath("$.data[0].updateTime").doesNotExist());
    }

    @Test
    void createReturnsPlainKeysOnceWithoutInternalFields() throws Exception {
        BaasProject project = project(10L, PROJECT_REF, 1L);
        when(lifecycleService.createProject(eq("demo"), eq(1L)))
            .thenReturn(new ProjectLifecycleService.CreatedProject(project, "pub_x", "sec_y"));

        mockMvc.perform(post("/studio/projects").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"demo\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.publishableKey").value("pub_x"))
            .andExpect(jsonPath("$.data.secretKey").value("sec_y"))
            .andExpect(jsonPath("$.data.project.projectRef").value(PROJECT_REF))
            .andExpect(jsonPath("$.data.project.id").doesNotExist())
            .andExpect(jsonPath("$.data.project.runtimeDbPasswordCipher").doesNotExist())
            .andExpect(jsonPath("$.data.project.runtimeDbUser").doesNotExist())
            .andExpect(jsonPath("$.data.project.dbName").doesNotExist())
            .andExpect(jsonPath("$.data.project.ownerUserId").doesNotExist())
            .andExpect(jsonPath("$.data.project.provisionStep").doesNotExist())
            .andExpect(jsonPath("$.data.project.deleteAfter").doesNotExist())
            .andExpect(jsonPath("$.data.project.updateTime").doesNotExist());
    }

    @Test
    void getForeignProjectReturns404WithoutLeak() throws Exception {
        when(accessService.requireOwned("foreignref0000000000")).thenThrow(new ProjectNotFoundException());

        mockMvc.perform(get("/studio/projects/foreignref0000000000"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(1))
            .andExpect(jsonPath("$.msg").value("项目不存在或无权访问"))
            .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void validationFailureReturns400AsR() throws Exception {
        mockMvc.perform(post("/studio/projects").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\" \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(1))
            .andExpect(jsonPath("$.msg").value("请求参数校验失败"))
            .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void pathVariableTypeMismatchReturns400AsR() throws Exception {
        mockMvc.perform(post("/studio/projects/" + PROJECT_REF + "/keys/not-a-number/revoke"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(1))
            .andExpect(jsonPath("$.msg").value("请求参数格式错误"))
            .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void malformedJsonReturns400AsR() throws Exception {
        mockMvc.perform(post("/studio/projects").contentType(MediaType.APPLICATION_JSON).content("{"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(1))
            .andExpect(jsonPath("$.msg").value("请求体格式错误"))
            .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void invalidEnumReturns400AsR() throws Exception {
        mockMvc.perform(post("/studio/projects/" + PROJECT_REF + "/keys")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"keyType\":\"ROOT\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(1))
            .andExpect(jsonPath("$.msg").value("请求体格式错误"))
            .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void illegalArgumentReturns400AsR() throws Exception {
        when(accessService.requireOwned(PROJECT_REF)).thenReturn(project(10L, PROJECT_REF, 1L));
        doThrow(new IllegalArgumentException("internal validation detail"))
            .when(keyService)
            .updateAllowedOrigins(10L, List.of("https://a.com"));

        mockMvc.perform(patch("/studio/projects/" + PROJECT_REF).contentType(MediaType.APPLICATION_JSON)
            .content("{\"allowedOrigins\":[\"https://a.com\"]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(1))
            .andExpect(jsonPath("$.msg").value("请求参数不合法"))
            .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void lifecycleStateConflictReturns409AsR() throws Exception {
        when(accessService.requireOwned(PROJECT_REF)).thenReturn(project(10L, PROJECT_REF, 1L));
        doThrow(new IllegalStateException("project not deletable or concurrent state change"))
            .when(lifecycleService)
            .deleteProject(10L, 1L);

        mockMvc.perform(delete("/studio/projects/" + PROJECT_REF))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(1))
            .andExpect(jsonPath("$.msg").value("当前项目状态不允许执行该操作"))
            .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void keyStateConflictReturns409AsR() throws Exception {
        when(accessService.requireOwned(PROJECT_REF)).thenReturn(project(10L, PROJECT_REF, 1L));
        when(keyService.createKey(10L, KeyType.PUBLISHABLE, 1L))
            .thenThrow(new IllegalStateException("project key operation cannot run within an active transaction"));

        mockMvc.perform(post("/studio/projects/" + PROJECT_REF + "/keys")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"keyType\":\"PUBLISHABLE\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(1))
            .andExpect(jsonPath("$.msg").value("当前项目状态不允许执行该操作"))
            .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void provisionFailureReturns500AsRWithoutLeak() throws Exception {
        when(lifecycleService.createProject(eq("demo"), eq(1L))).thenThrow(new ProjectProvisionException(
                "provision failed at INIT", new RuntimeException("jdbc:mysql://internal?password=should-never-leak")));

        mockMvc.perform(post("/studio/projects").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"demo\"}"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value(1))
            .andExpect(jsonPath("$.msg").value("项目开通失败，请稍后重试"))
            .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.not(
                    org.hamcrest.Matchers.containsString("should-never-leak"))))
            .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void unexpectedExceptionReturns500AsRWithoutLeak() throws Exception {
        when(accessService.listVisible())
            .thenThrow(new RuntimeException("jdbc:mysql://internal?password=should-never-leak"));

        mockMvc.perform(get("/studio/projects"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value(1))
            .andExpect(jsonPath("$.msg").value("服务暂时不可用，请稍后重试"))
            .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.not(
                    org.hamcrest.Matchers.containsString("should-never-leak"))))
            .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void getOwnedProjectReturnsSafeVoDetail() throws Exception {
        when(accessService.requireOwned(PROJECT_REF)).thenReturn(project(10L, PROJECT_REF, 1L));

        mockMvc.perform(get("/studio/projects/" + PROJECT_REF))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.projectRef").value(PROJECT_REF))
            .andExpect(jsonPath("$.data.runtimeDbPasswordCipher").doesNotExist())
            .andExpect(jsonPath("$.data.runtimeDbUser").doesNotExist())
            .andExpect(jsonPath("$.data.dbName").doesNotExist())
            .andExpect(jsonPath("$.data.ownerUserId").doesNotExist());
    }

    @Test
    void patchUpdatesAllowedOrigins() throws Exception {
        when(accessService.requireOwned(PROJECT_REF)).thenReturn(project(10L, PROJECT_REF, 1L));

        mockMvc.perform(patch("/studio/projects/" + PROJECT_REF).contentType(MediaType.APPLICATION_JSON)
            .content("{\"allowedOrigins\":[\"https://a.com\"]}"))
            .andExpect(status().isOk());

        verify(keyService).updateAllowedOrigins(eq(10L), eq(List.of("https://a.com")));
    }

    @Test
    void deleteTriggersLifecycle() throws Exception {
        when(accessService.requireOwned(PROJECT_REF)).thenReturn(project(10L, PROJECT_REF, 1L));

        mockMvc.perform(delete("/studio/projects/" + PROJECT_REF)).andExpect(status().isOk());

        verify(lifecycleService).deleteProject(10L, 1L);
    }

    @Test
    void reconcileRequiresOwnedProjectAndDelegatesBodyOperationId() throws Exception {
        BaasProject owned = project(10L, PROJECT_REF, 1L);
        String operationId = "11111111-1111-1111-1111-111111111111";
        ObjectNode report = objectMapper.createObjectNode();
        report.putArray("corrected").add("orders");
        when(accessService.requireOwned(PROJECT_REF)).thenReturn(owned);
        when(reconcileService.manualReconcile(any(BaasProject.class), any(ReconcileTriggerDTO.class)))
            .thenReturn(report);

        mockMvc.perform(post("/studio/projects/" + PROJECT_REF + "/reconcile")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"operationId\":\"" + operationId + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.corrected[0]").value("orders"));

        verify(accessService).requireOwned(PROJECT_REF);
        verify(reconcileService).manualReconcile(eq(owned), eq(new ReconcileTriggerDTO(operationId)));
    }

    @Test
    void reconcileRejectsBlankOperationIdBeforeOwnershipLookup() throws Exception {
        mockMvc.perform(post("/studio/projects/" + PROJECT_REF + "/reconcile")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"operationId\":\" \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.msg").value("请求参数校验失败"));

        Mockito.verifyNoInteractions(accessService, reconcileService);
    }

    @Test
    void nonAdminCannotTriggerSystemTableMigrationEvenForOwnedProject() throws Exception {
        BaasProject owned = project(10L, PROJECT_REF, 1L);
        when(accessService.requireOwned(PROJECT_REF)).thenReturn(owned);
        when(userProvider.isBaasAdmin()).thenReturn(false);

        mockMvc.perform(post("/studio/projects/" + PROJECT_REF + "/system-tables/migrate"))
            .andExpect(status().isNotFound());

        verifyNoInteractions(migrationService);
    }

    @Test
    void adminGetsSynchronousMigrationResult() throws Exception {
        BaasProject owned = project(10L, PROJECT_REF, 1L);
        when(accessService.requireOwned(PROJECT_REF)).thenReturn(owned);
        when(userProvider.isBaasAdmin()).thenReturn(true);
        when(migrationService.migrate(owned)).thenReturn(new SystemTableMigrationResult("ACTIVE", true));

        mockMvc.perform(post("/studio/projects/" + PROJECT_REF + "/system-tables/migrate"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("ACTIVE"))
            .andExpect(jsonPath("$.data.migrated").value(true));

        verify(migrationService).migrate(owned);
    }

    @Test
    void listKeysReturnsSafeKeyVos() throws Exception {
        when(accessService.requireOwned(PROJECT_REF)).thenReturn(project(10L, PROJECT_REF, 1L));
        when(keyService.listKeys(10L)).thenReturn(List.of(
                new ApiKeyVO(KEY_ID, KeyType.PUBLISHABLE.name(), "pub_abcdefgh", "ACTIVE", LocalDateTime.now())));

        mockMvc.perform(get("/studio/projects/" + PROJECT_REF + "/keys"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].id").isString())
            .andExpect(jsonPath("$.data[0].id").value(KEY_ID))
            .andExpect(jsonPath("$.data[0].keyPrefix").value("pub_abcdefgh"))
            .andExpect(jsonPath("$.data[0].keyHash").doesNotExist())
            .andExpect(jsonPath("$.data[0].plaintext").doesNotExist());

        verify(keyService).listKeys(10L);
    }

    @Test
    void createKeyDelegates() throws Exception {
        when(accessService.requireOwned(PROJECT_REF)).thenReturn(project(10L, PROJECT_REF, 1L));
        when(keyService.createKey(10L, KeyType.PUBLISHABLE, 1L))
            .thenReturn(new CreatedKeyVO(KEY_ID, KeyType.PUBLISHABLE.name(), "pub_abcdefgh-secret"));

        mockMvc.perform(post("/studio/projects/" + PROJECT_REF + "/keys")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"keyType\":\"PUBLISHABLE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").isString())
            .andExpect(jsonPath("$.data.id").value(KEY_ID));

        verify(keyService).createKey(10L, KeyType.PUBLISHABLE, 1L);
    }

    @Test
    void revokeKeyDelegatesWithProjectScope() throws Exception {
        when(accessService.requireOwned(PROJECT_REF)).thenReturn(project(10L, PROJECT_REF, 1L));

        mockMvc.perform(post("/studio/projects/" + PROJECT_REF + "/keys/5/revoke"))
            .andExpect(status().isOk());

        verify(keyService).revokeKey(10L, 5L, 1L);
    }

}
