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

import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.exception.BaasStudioExceptionHandler;
import com.aiwork.baas.exception.ProjectNotFoundException;
import com.aiwork.baas.service.ProjectAccessService;
import com.aiwork.baas.service.TableManagementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StudioTableControllerTest {

    private static final String PROJECT_REF = "abcdefghijabcdefghij";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ProjectAccessService accessService;

    private TableManagementService tableService;

    private BaasProject project;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        accessService = Mockito.mock(ProjectAccessService.class);
        tableService = Mockito.mock(TableManagementService.class);
        project = new BaasProject();
        project.setId(10L);
        project.setProjectRef(PROJECT_REF);
        mockMvc = MockMvcBuilders.standaloneSetup(new StudioTableController(accessService, tableService))
            .setControllerAdvice(new BaasStudioExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
    }

    @Test
    void createChecksOwnershipBeforeDelegatingAndReturnsSnapshot() throws Exception {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("tableName", "orders");
        snapshot.put("status", "ACTIVE");
        when(accessService.requireOwned(PROJECT_REF)).thenReturn(project);
        when(tableService.createTable(Mockito.eq(project), Mockito.any())).thenReturn(snapshot);

        mockMvc.perform(post("/studio/projects/" + PROJECT_REF + "/tables")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"operationId\":\"6f9e6d0e-b30c-4e20-8101-4d1b8cf1ee54\","
                    + "\"tableName\":\"orders\",\"columns\":[{\"columnName\":\"name\","
                    + "\"dataType\":\"varchar\",\"length\":64}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.tableName").value("orders"))
            .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        InOrder order = inOrder(accessService, tableService);
        order.verify(accessService).requireOwned(PROJECT_REF);
        order.verify(tableService).createTable(Mockito.eq(project), Mockito.argThat(dto ->
                "orders".equals(dto.tableName()) && dto.columns().size() == 1));
    }

    @Test
    void alterChecksOwnershipBeforeDelegatingAndReturnsSnapshot() throws Exception {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("tableName", "orders");
        snapshot.put("comment", "新注释");
        when(accessService.requireOwned(PROJECT_REF)).thenReturn(project);
        when(tableService.alterTable(Mockito.eq(project), Mockito.eq("orders"), Mockito.any()))
            .thenReturn(snapshot);

        mockMvc.perform(patch("/studio/projects/" + PROJECT_REF + "/tables/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"operationId\":\"6f9e6d0e-b30c-4e20-8101-4d1b8cf1ee54\",\"comment\":\"新注释\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.tableName").value("orders"))
            .andExpect(jsonPath("$.data.comment").value("新注释"));

        InOrder order = inOrder(accessService, tableService);
        order.verify(accessService).requireOwned(PROJECT_REF);
        order.verify(tableService).alterTable(Mockito.eq(project), Mockito.eq("orders"), Mockito.argThat(dto ->
                "新注释".equals(dto.comment())));
    }

    @Test
    void dropChecksOwnershipBeforeDelegatingAndReturnsTombstoneSnapshot() throws Exception {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("tableName", "orders");
        snapshot.put("status", "DELETED");
        String operationId = "6f9e6d0e-b30c-4e20-8101-4d1b8cf1ee54";
        when(accessService.requireOwned(PROJECT_REF)).thenReturn(project);
        when(tableService.dropTable(project, "orders", operationId)).thenReturn(snapshot);

        mockMvc.perform(delete("/studio/projects/" + PROJECT_REF + "/tables/orders")
            .param("operationId", operationId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.tableName").value("orders"))
            .andExpect(jsonPath("$.data.status").value("DELETED"));

        InOrder order = inOrder(accessService, tableService);
        order.verify(accessService).requireOwned(PROJECT_REF);
        order.verify(tableService).dropTable(project, "orders", operationId);
    }

    @Test
    void foreignProjectStopsBeforeTableService() throws Exception {
        when(accessService.requireOwned(PROJECT_REF)).thenThrow(new ProjectNotFoundException());

        mockMvc.perform(get("/studio/projects/" + PROJECT_REF + "/tables"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.msg").value("项目不存在或无权访问"));

        verifyNoInteractions(tableService);
    }

    @Test
    void listAndDetailReturnContractShapes() throws Exception {
        ArrayNode list = objectMapper.createArrayNode();
        list.addObject().put("tableName", "orders").put("status", "ACTIVE");
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("tableName", "orders");
        detail.putArray("columns");
        detail.putObject("acl").putObject("anon").put("select", false);
        when(accessService.requireOwned(PROJECT_REF)).thenReturn(project);
        when(tableService.listTables(project)).thenReturn(list);
        when(tableService.getTableSnapshot(project, "orders")).thenReturn(detail);

        mockMvc.perform(get("/studio/projects/" + PROJECT_REF + "/tables"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].tableName").value("orders"));
        mockMvc.perform(get("/studio/projects/" + PROJECT_REF + "/tables/orders"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.tableName").value("orders"))
            .andExpect(jsonPath("$.data.columns").isArray())
            .andExpect(jsonPath("$.data.acl.anon.select").value(false));
    }

    @Test
    void invalidCreateBodyReturnsValidationErrorWithoutAccessLookup() throws Exception {
        mockMvc.perform(post("/studio/projects/" + PROJECT_REF + "/tables")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"operationId\":\"\",\"tableName\":\"orders\",\"columns\":[]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.msg").value("请求参数校验失败"));

        verifyNoInteractions(accessService, tableService);
    }

}
