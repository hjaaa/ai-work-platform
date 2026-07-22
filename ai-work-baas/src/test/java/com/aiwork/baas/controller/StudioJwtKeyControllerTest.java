package com.aiwork.baas.controller;

import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.exception.BaasStudioExceptionHandler;
import com.aiwork.baas.exception.ProjectNotFoundException;
import com.aiwork.baas.security.CurrentUserProvider;
import com.aiwork.baas.service.JwtKeyRotationService;
import com.aiwork.baas.service.ProjectAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * StudioJwtKeyController 路由与归属校验(spec §14 IDOR 必测)。
 */
class StudioJwtKeyControllerTest {

    private static final String PROJECT_REF = "abcdefghijabcdefghij";

    private static final long OPERATOR_ID = 777L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ProjectAccessService accessService;

    private JwtKeyRotationService rotationService;

    private CurrentUserProvider userProvider;

    private BaasProject project;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        accessService = Mockito.mock(ProjectAccessService.class);
        rotationService = Mockito.mock(JwtKeyRotationService.class);
        userProvider = Mockito.mock(CurrentUserProvider.class);
        project = new BaasProject();
        project.setId(10L);
        project.setProjectRef(PROJECT_REF);
        when(userProvider.currentUserId()).thenReturn(OPERATOR_ID);
        mockMvc = MockMvcBuilders
            .standaloneSetup(new StudioJwtKeyController(accessService, rotationService, userProvider))
            .setControllerAdvice(new BaasStudioExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
    }

    @Test
    void rotateChecksOwnershipBeforeDelegatingAndPassesOperator() throws Exception {
        when(accessService.requireOwned(PROJECT_REF)).thenReturn(project);
        when(rotationService.rotate(project, OPERATOR_ID))
            .thenReturn(new JwtKeyRotationService.RotatedKey("kid-new"));

        mockMvc.perform(post("/studio/projects/" + PROJECT_REF + "/jwt-keys/rotate"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.kid").value("kid-new"));

        InOrder order = inOrder(accessService, rotationService);
        order.verify(accessService).requireOwned(PROJECT_REF);
        order.verify(rotationService).rotate(project, OPERATOR_ID);
    }

    @Test
    void emergencyRotateChecksOwnershipBeforeDelegatingAndPassesOperator() throws Exception {
        when(accessService.requireOwned(PROJECT_REF)).thenReturn(project);
        when(rotationService.emergencyRotate(project, OPERATOR_ID))
            .thenReturn(new JwtKeyRotationService.RotatedKey("kid-emg"));

        mockMvc.perform(post("/studio/projects/" + PROJECT_REF + "/jwt-keys/emergency-rotate"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.kid").value("kid-emg"));

        InOrder order = inOrder(accessService, rotationService);
        order.verify(accessService).requireOwned(PROJECT_REF);
        order.verify(rotationService).emergencyRotate(project, OPERATOR_ID);
    }

    @Test
    void foreignProjectRefStopsBeforeRotationService() throws Exception {
        when(accessService.requireOwned(PROJECT_REF)).thenThrow(new ProjectNotFoundException());

        mockMvc.perform(post("/studio/projects/" + PROJECT_REF + "/jwt-keys/rotate"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.msg").value("项目不存在或无权访问"));
        mockMvc.perform(post("/studio/projects/" + PROJECT_REF + "/jwt-keys/emergency-rotate"))
            .andExpect(status().isNotFound());

        // requireOwned 先抛出 → 轮换 Service 零调用(IDOR 不可能穿透到业务逻辑)
        verifyNoInteractions(rotationService);
    }

}
