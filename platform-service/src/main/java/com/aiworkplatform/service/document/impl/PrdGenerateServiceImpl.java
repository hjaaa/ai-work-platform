package com.aiworkplatform.service.document.impl;

import com.aiworkplatform.domain.entity.Generation;
import com.aiworkplatform.domain.enums.GenerationStatus;
import com.aiworkplatform.domain.enums.GenerationType;
import com.aiworkplatform.domain.mapper.GenerationMapper;
import com.aiworkplatform.service.chat.ChatMessage;
import com.aiworkplatform.service.chat.MessagePushService;
import com.aiworkplatform.service.document.PrdGenerateService;
import com.aiworkplatform.service.document.PrdPromptTemplate;
import com.aiworkplatform.service.orchestrator.ClaudeCodeOrchestrator;
import com.aiworkplatform.service.orchestrator.OrchestratorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PrdGenerateServiceImpl implements PrdGenerateService {

    private static final Logger log = LoggerFactory.getLogger(PrdGenerateServiceImpl.class);

    private static final Pattern PRD_PATTERN = Pattern.compile("\\[PRD](.*?)\\[/PRD]", Pattern.DOTALL);
    private static final Pattern QUESTIONS_PATTERN = Pattern.compile("\\[QUESTIONS](.*?)\\[/QUESTIONS]", Pattern.DOTALL);

    private final ClaudeCodeOrchestrator orchestrator;
    private final OrchestratorConfig orchestratorConfig;
    private final GenerationMapper generationMapper;
    private final MessagePushService messagePushService;

    public PrdGenerateServiceImpl(ClaudeCodeOrchestrator orchestrator,
                                  OrchestratorConfig orchestratorConfig,
                                  GenerationMapper generationMapper,
                                  MessagePushService messagePushService) {
        this.orchestrator = orchestrator;
        this.orchestratorConfig = orchestratorConfig;
        this.generationMapper = generationMapper;
        this.messagePushService = messagePushService;
    }

    @Async
    @Override
    public void generateInitialPrd(String projectId, String userDescription) {
        String prompt = PrdPromptTemplate.buildInitialPrompt(userDescription);
        executePrdGeneration(projectId, prompt);
    }

    @Async
    @Override
    public void refinePrd(String projectId, String currentPrd, String userAnswer) {
        String prompt = PrdPromptTemplate.buildRefinementPrompt(currentPrd, userAnswer);
        executePrdGeneration(projectId, prompt);
    }

    @Async
    @Override
    public void finalizePrd(String projectId, String currentPrd) {
        String prompt = PrdPromptTemplate.buildFinalizePrompt(currentPrd);
        executePrdGeneration(projectId, prompt);
    }

    private void executePrdGeneration(String projectId, String prompt) {
        Generation generation = new Generation();
        generation.setProjectId(projectId);
        generation.setType(GenerationType.PRD.getValue());
        generation.setPrompt(prompt);
        generation.setStatus(GenerationStatus.RUNNING.getValue());
        generationMapper.insert(generation);

        long startTime = System.currentTimeMillis();
        messagePushService.pushProgress(projectId, "正在生成 PRD 文档...");

        try {
            Path workDir = Path.of(orchestratorConfig.getWorkspaceBasePath());
            String result = orchestrator.execute(projectId, workDir, prompt);

            PrdParseResult parsed = parseOutput(result);

            generation.setStatus(GenerationStatus.SUCCESS.getValue());
            generation.setDurationMs((int) (System.currentTimeMillis() - startTime));
            generation.setPrdContent(parsed.prdContent());
            generationMapper.updateById(generation);

            if (parsed.prdContent() != null) {
                messagePushService.pushToProject(projectId,
                        ChatMessage.builder()
                                .projectId(projectId)
                                .role("assistant")
                                .content(parsed.prdContent())
                                .messageType("prd")
                                .timestamp(System.currentTimeMillis())
                                .build());
            }

            if (parsed.questions() != null && !parsed.questions().isEmpty()) {
                StringBuilder questionMsg = new StringBuilder("我有几个问题需要确认：\n\n");
                for (String q : parsed.questions()) {
                    questionMsg.append(q).append("\n");
                }
                messagePushService.pushAssistantMessage(projectId, questionMsg.toString());
            }

            messagePushService.pushProgress(projectId,
                    "PRD 生成完成，耗时 " + generation.getDurationMs() / 1000 + " 秒");

        } catch (Exception e) {
            log.error("PRD 生成失败: projectId={}", projectId, e);
            generation.setStatus(GenerationStatus.FAILED.getValue());
            generation.setDurationMs((int) (System.currentTimeMillis() - startTime));
            generation.setErrorMessage(e.getMessage());
            generationMapper.updateById(generation);

            messagePushService.pushAssistantMessage(projectId, "PRD 生成失败: " + e.getMessage());
        }
    }

    PrdParseResult parseOutput(String output) {
        String prdContent = null;
        List<String> questions = List.of();

        Matcher prdMatcher = PRD_PATTERN.matcher(output);
        if (prdMatcher.find()) {
            prdContent = prdMatcher.group(1).trim();
        }

        Matcher questionMatcher = QUESTIONS_PATTERN.matcher(output);
        if (questionMatcher.find()) {
            String questionBlock = questionMatcher.group(1).trim();
            questions = questionBlock.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .toList();
        }

        return new PrdParseResult(prdContent, questions);
    }

    record PrdParseResult(String prdContent, List<String> questions) {
    }
}
