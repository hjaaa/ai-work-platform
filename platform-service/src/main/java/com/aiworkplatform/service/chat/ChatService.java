package com.aiworkplatform.service.chat;

import com.aiworkplatform.domain.entity.Conversation;
import com.aiworkplatform.domain.entity.Generation;
import com.aiworkplatform.domain.entity.Project;
import com.aiworkplatform.domain.enums.GenerationStatus;
import com.aiworkplatform.domain.enums.GenerationType;
import com.aiworkplatform.domain.mapper.ConversationMapper;
import com.aiworkplatform.domain.mapper.GenerationMapper;
import com.aiworkplatform.service.orchestrator.ClaudeCodeOrchestrator;
import com.aiworkplatform.service.project.ProjectService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/**
 * 对话服务
 * 接收用户消息 → 持久化 → 调用 Claude Code → 持久化结果 → 推送
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ConversationMapper conversationMapper;
    private final GenerationMapper generationMapper;
    private final ProjectService projectService;
    private final ClaudeCodeOrchestrator orchestrator;
    private final MessagePushService messagePushService;
    private final ContextWindowManager contextWindowManager;

    public ChatService(ConversationMapper conversationMapper,
                       GenerationMapper generationMapper,
                       ProjectService projectService,
                       ClaudeCodeOrchestrator orchestrator,
                       MessagePushService messagePushService,
                       ContextWindowManager contextWindowManager) {
        this.conversationMapper = conversationMapper;
        this.generationMapper = generationMapper;
        this.projectService = projectService;
        this.orchestrator = orchestrator;
        this.messagePushService = messagePushService;
        this.contextWindowManager = contextWindowManager;
    }

    /**
     * 处理用户发送的对话消息
     */
    @Async
    public void handleUserMessage(String projectId, String content) {
        // 1. 持久化用户消息
        saveConversation(projectId, "user", content, "text");

        // 2. 获取项目信息
        Project project = projectService.getByProjectId(projectId);

        // 3. 构建 prompt（复用 getHistory 查询，避免重复 DB 调用）
        List<Conversation> history = getHistory(projectId);
        String prompt = buildPrompt(history, content);

        // 4. 创建生成记录
        Generation generation = new Generation();
        generation.setProjectId(projectId);
        generation.setType(GenerationType.CODE.getValue());
        generation.setPrompt(prompt);
        generation.setStatus(GenerationStatus.RUNNING.getValue());
        generationMapper.insert(generation);

        // 5. 调用 Claude Code CLI
        long startTime = System.currentTimeMillis();
        try {
            String result = orchestrator.execute(projectId, Path.of(project.getWorkspacePath()), prompt);

            // 6. 更新生成记录
            generation.setStatus(GenerationStatus.SUCCESS.getValue());
            generation.setDurationMs((int) (System.currentTimeMillis() - startTime));
            generationMapper.updateById(generation);

            // 7. 持久化 AI 回复
            saveConversation(projectId, "assistant", result, "code");

            messagePushService.pushProgress(projectId, "代码生成完成，耗时 " + generation.getDurationMs() / 1000 + " 秒");

        } catch (Exception e) {
            log.error("代码生成失败: projectId={}", projectId, e);
            generation.setStatus(GenerationStatus.FAILED.getValue());
            generation.setDurationMs((int) (System.currentTimeMillis() - startTime));
            generation.setErrorMessage(e.getMessage());
            generationMapper.updateById(generation);

            messagePushService.pushAssistantMessage(projectId, "生成失败: " + e.getMessage());
        }
    }

    /**
     * 获取项目的历史对话
     */
    public List<Conversation> getHistory(String projectId) {
        return conversationMapper.selectList(
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getProjectId, projectId)
                        .orderByAsc(Conversation::getCreatedAt));
    }

    private void saveConversation(String projectId, String role, String content, String messageType) {
        Conversation conversation = new Conversation();
        conversation.setProjectId(projectId);
        conversation.setRole(role);
        conversation.setContent(content);
        conversation.setMessageType(messageType);
        conversationMapper.insert(conversation);
    }

    private String buildPrompt(List<Conversation> history, String userMessage) {
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("你是一个代码生成助手，根据产品经理的需求生成 Spring Boot + Vue 代码。\n\n");

        // 使用上下文窗口管理器构建压缩后的历史
        String context = contextWindowManager.buildContext(history);
        if (!context.isEmpty()) {
            promptBuilder.append(context).append("\n");
        }

        promptBuilder.append("## 当前需求\n");
        promptBuilder.append(userMessage);

        return promptBuilder.toString();
    }
}
