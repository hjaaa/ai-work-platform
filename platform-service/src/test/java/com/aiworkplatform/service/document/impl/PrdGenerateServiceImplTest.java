package com.aiworkplatform.service.document.impl;

import com.aiworkplatform.domain.mapper.GenerationMapper;
import com.aiworkplatform.service.chat.MessagePushService;
import com.aiworkplatform.service.orchestrator.ClaudeCodeOrchestrator;
import com.aiworkplatform.service.orchestrator.OrchestratorConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class PrdGenerateServiceImplTest {

    private PrdGenerateServiceImpl prdGenerateService;

    @BeforeEach
    void setUp() {
        prdGenerateService = new PrdGenerateServiceImpl(
                mock(ClaudeCodeOrchestrator.class),
                new OrchestratorConfig(),
                mock(GenerationMapper.class),
                mock(MessagePushService.class)
        );
    }

    @Test
    void should_parsePrdContent_when_outputContainsPrdBlock() {
        String output = """
                一些前置说明文字
                [PRD]
                # 员工管理系统
                ## 1. 背景与目标
                管理员工信息
                [/PRD]
                一些后置文字
                """;

        PrdGenerateServiceImpl.PrdParseResult result = prdGenerateService.parseOutput(output);

        assertNotNull(result.prdContent());
        assertTrue(result.prdContent().contains("员工管理系统"));
        assertTrue(result.prdContent().contains("背景与目标"));
    }

    @Test
    void should_parseQuestions_when_outputContainsQuestionsBlock() {
        String output = """
                [QUESTIONS]
                1. 需要支持哪些假期类型？
                2. 审批流程是几级审批？
                [/QUESTIONS]
                [PRD]
                # 请假管理
                [/PRD]
                """;

        PrdGenerateServiceImpl.PrdParseResult result = prdGenerateService.parseOutput(output);

        assertNotNull(result.questions());
        assertEquals(2, result.questions().size());
        assertTrue(result.questions().get(0).contains("假期类型"));
    }

    @Test
    void should_returnEmptyQuestions_when_noQuestionsBlock() {
        String output = """
                [PRD]
                # 简单功能
                [/PRD]
                """;

        PrdGenerateServiceImpl.PrdParseResult result = prdGenerateService.parseOutput(output);

        assertNotNull(result.prdContent());
        assertTrue(result.questions().isEmpty());
    }

    @Test
    void should_returnNullPrd_when_noPrdBlock() {
        String output = "这是一段没有PRD标记的普通文本";

        PrdGenerateServiceImpl.PrdParseResult result = prdGenerateService.parseOutput(output);

        assertNull(result.prdContent());
    }
}
