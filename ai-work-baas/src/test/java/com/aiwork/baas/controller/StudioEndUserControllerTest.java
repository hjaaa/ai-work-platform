package com.aiwork.baas.controller;

import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.exception.BaasStudioExceptionHandler;
import com.aiwork.baas.exception.ProjectNotFoundException;
import com.aiwork.baas.security.CurrentUserProvider;
import com.aiwork.baas.service.EndUserAdminService;
import com.aiwork.baas.service.ProjectAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * StudioEndUserController 路由与归属校验(spec §14 IDOR 必测)。
 */
class StudioEndUserControllerTest {

    private static final String PROJECT_REF = "abcdefghijabcdefghij";

    private static final long OPERATOR_ID = 888L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ProjectAccessService accessService;

    private EndUserAdminService adminService;

    private CurrentUserProvider userProvider;

    private BaasProject project;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        accessService = Mockito.mock(ProjectAccessService.class);
        adminService = Mockito.mock(EndUserAdminService.class);
        userProvider = Mockito.mock(CurrentUserProvider.class);
        project = new BaasProject();
        project.setId(10L);
        project.setProjectRef(PROJECT_REF);
        when(userProvider.currentUserId()).thenReturn(OPERATOR_ID);
        mockMvc = MockMvcBuilders
            .standaloneSetup(new StudioEndUserController(accessService, adminService, userProvider))
            .setControllerAdvice(new BaasStudioExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
    }

    @Test
    void listChecksOwnershipBeforeDelegating() throws Exception {
        when(accessService.requireOwned(PROJECT_REF)).thenReturn(project);
        when(adminService.list(project, 2, 20))
            .thenReturn(new EndUserAdminService.UserPage(0L, List.of()));

        mockMvc.perform(get("/studio/projects/" + PROJECT_REF + "/users").param("page", "2").param("size", "20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(0));

        InOrder order = inOrder(accessService, adminService);
        order.verify(accessService).requireOwned(PROJECT_REF);
        order.verify(adminService).list(project, 2, 20);
    }

    @Test
    void softDeleteChecksOwnershipBeforeDelegatingAndPassesOperator() throws Exception {
        when(accessService.requireOwned(PROJECT_REF)).thenReturn(project);

        mockMvc.perform(delete("/studio/projects/" + PROJECT_REF + "/users/42"))
            .andExpect(status().isOk());

        InOrder order = inOrder(accessService, adminService);
        order.verify(accessService).requireOwned(PROJECT_REF);
        order.verify(adminService).softDelete(project, 42L, OPERATOR_ID);
    }

    @Test
    void restoreChecksOwnershipBeforeDelegatingAndPassesOperator() throws Exception {
        when(accessService.requireOwned(PROJECT_REF)).thenReturn(project);

        mockMvc.perform(post("/studio/projects/" + PROJECT_REF + "/users/42/restore"))
            .andExpect(status().isOk());

        InOrder order = inOrder(accessService, adminService);
        order.verify(accessService).requireOwned(PROJECT_REF);
        order.verify(adminService).restore(project, 42L, OPERATOR_ID);
    }

    @Test
    void foreignProjectRefStopsBeforeAdminService() throws Exception {
        when(accessService.requireOwned(PROJECT_REF)).thenThrow(new ProjectNotFoundException());

        mockMvc.perform(get("/studio/projects/" + PROJECT_REF + "/users"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.msg").value("项目不存在或无权访问"));
        mockMvc.perform(delete("/studio/projects/" + PROJECT_REF + "/users/42"))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/studio/projects/" + PROJECT_REF + "/users/42/restore"))
            .andExpect(status().isNotFound());

        // 他人 {ref} 一律在 requireOwned 处 404,业务 Service 零调用
        verifyNoInteractions(adminService);
    }

}
