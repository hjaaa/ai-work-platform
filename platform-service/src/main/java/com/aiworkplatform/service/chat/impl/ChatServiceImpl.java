package com.aiworkplatform.service.chat.impl;

import com.aiworkplatform.domain.entity.ChatThread;
import com.aiworkplatform.domain.entity.Conversation;
import com.aiworkplatform.domain.entity.Generation;
import com.aiworkplatform.domain.entity.Project;
import com.aiworkplatform.domain.enums.GenerationStatus;
import com.aiworkplatform.domain.enums.GenerationType;
import com.aiworkplatform.domain.mapper.ConversationMapper;
import com.aiworkplatform.domain.mapper.GenerationMapper;
import com.aiworkplatform.service.chat.ChatService;
import com.aiworkplatform.service.chat.FilePromptBuilder;
import com.aiworkplatform.service.chat.MessagePushService;
import com.aiworkplatform.service.chat.SkillPromptConverter;
import com.aiworkplatform.service.orchestrator.ClaudeCodeOrchestrator;
import com.aiworkplatform.service.orchestrator.ExecutionResult;
import com.aiworkplatform.service.project.ProjectService;
import com.aiworkplatform.service.thread.ChatThreadService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);
    private static final int TITLE_MAX_LENGTH = 30;

    private final ConversationMapper conversationMapper;
    private final GenerationMapper generationMapper;
    private final ProjectService projectService;
    private final ChatThreadService chatThreadService;
    private final ClaudeCodeOrchestrator orchestrator;
    private final MessagePushService messagePushService;

    /** 记录已被用户取消的线程，防止异步方法在取消后仍保存 assistant 消息 */
    private final Set<String> cancelledThreads = ConcurrentHashMap.newKeySet();

    public ChatServiceImpl(ConversationMapper conversationMapper,
                           GenerationMapper generationMapper,
                           ProjectService projectService,
                           ChatThreadService chatThreadService,
                           ClaudeCodeOrchestrator orchestrator,
                           MessagePushService messagePushService) {
        this.conversationMapper = conversationMapper;
        this.generationMapper = generationMapper;
        this.projectService = projectService;
        this.chatThreadService = chatThreadService;
        this.orchestrator = orchestrator;
        this.messagePushService = messagePushService;
    }

    @Async
    @Override
    public void handleThreadMessage(String threadId, String content) {
        handleThreadMessage(threadId, content, null);
    }

    @Async
    @Override
    public void handleThreadMessage(String threadId, String content, List<String> filePaths) {
        ChatThread thread = chatThreadService.getByThreadId(threadId);
        String projectId = thread.getProjectId();

        // 1. 持久化用户消息
        saveConversation(projectId, threadId, "user", content, "text");

        // 2. 如果是线程的第一条用户消息，自动生成标题
        autoGenerateTitle(thread, content);

        // 3. 获取项目信息
        Project project = projectService.getByProjectId(projectId);

        // 4. 创建生成记录
        Generation generation = new Generation();
        generation.setProjectId(projectId);
        generation.setType(GenerationType.CODE.getValue());
        generation.setPrompt(content);
        generation.setStatus(GenerationStatus.RUNNING.getValue());
        generationMapper.insert(generation);

        // 5. 调用 Claude Code CLI（session 模式，不再手动拼历史）
        // $skill-name 格式命令转换为自然语言 prompt，让 CC CLI 触发 Skill 工具
        // 文件路径拼入 prompt，让 CC CLI 通过 Read 工具读取文件
        String cliPrompt = SkillPromptConverter.convert(content);
        cliPrompt = FilePromptBuilder.build(cliPrompt, filePaths);
        long startTime = System.currentTimeMillis();
        try {
            String sessionId = thread.getClaudeSessionId();
            ExecutionResult result = orchestrator.execute(
                    threadId, Path.of(project.getWorkspacePath()), cliPrompt, sessionId);

            if (result.isCancelled()) {
                cancelledThreads.remove(threadId);
                generation.setStatus(GenerationStatus.FAILED.getValue());
                generation.setDurationMs((int) (System.currentTimeMillis() - startTime));
                generation.setErrorMessage("用户取消");
                generationMapper.updateById(generation);

                messagePushService.pushProgressToThread(threadId, projectId, "已取消生成");
                return;
            }

            // 检查是否在生成过程中被用户取消（进程已完成但取消请求随后到达的竞态情况）
            if (cancelledThreads.remove(threadId)) {
                log.info("生成已完成但用户已取消，丢弃结果: threadId={}", threadId);
                generation.setStatus(GenerationStatus.FAILED.getValue());
                generation.setDurationMs((int) (System.currentTimeMillis() - startTime));
                generation.setErrorMessage("用户取消");
                generationMapper.updateById(generation);
                return;
            }

            generation.setStatus(GenerationStatus.SUCCESS.getValue());
            generation.setDurationMs((int) (System.currentTimeMillis() - startTime));
            generationMapper.updateById(generation);

            // 首次对话时保存 session ID
            if (sessionId == null && result.getSessionId() != null) {
                chatThreadService.updateClaudeSessionId(threadId, result.getSessionId());
            }

            saveConversation(projectId, threadId, "assistant", result.getOutput(), "code");
            // AI 回复已由 StreamJsonParser 流式推送，此处不再重复推送
            messagePushService.pushProgressToThread(threadId, projectId,
                    "回答完毕，耗时 " + generation.getDurationMs() / 1000 + " 秒");

        } catch (Exception e) {
            log.error("代码生成失败: projectId={}, threadId={}", projectId, threadId, e);
            generation.setStatus(GenerationStatus.FAILED.getValue());
            generation.setDurationMs((int) (System.currentTimeMillis() - startTime));
            generation.setErrorMessage(e.getMessage());
            generationMapper.updateById(generation);

            messagePushService.pushAssistantMessageToThread(threadId, projectId,
                    "生成失败: " + e.getMessage());
        }
    }

    @Override
    public void cancelThread(String threadId) {
        // 标记线程为已取消，阻止异步方法后续保存 assistant 消息
        cancelledThreads.add(threadId);

        boolean processKilled = orchestrator.cancel(threadId);
        if (processKilled) {
            log.info("取消线程生成（进程已终止）: threadId={}", threadId);
        } else {
            // 进程已结束，assistant 消息可能已保存到数据库，需要清理
            log.info("线程进程已结束，清理已保存的 assistant 消息: threadId={}", threadId);
            deleteLatestAssistantMessage(threadId);
        }
    }

    @Override
    public List<Conversation> getHistoryByThread(String threadId) {
        return conversationMapper.selectList(
                new QueryWrapper<Conversation>()
                        .eq("thread_id", threadId)
                        .orderByAsc("created_at"));
    }

    @Deprecated
    @Async
    @Override
    public void handleUserMessage(String projectId, String content) {
        ChatThread defaultThread = chatThreadService.getOrCreateDefaultThread(projectId);
        handleThreadMessage(defaultThread.getThreadId(), content);
    }

    @Deprecated
    @Override
    public List<Conversation> getHistory(String projectId) {
        return conversationMapper.selectList(
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getProjectId, projectId)
                        .orderByAsc(Conversation::getCreatedAt));
    }

    private void saveConversation(String projectId, String threadId,
                                  String role, String content, String messageType) {
        Conversation conversation = new Conversation();
        conversation.setProjectId(projectId);
        conversation.setThreadId(threadId);
        conversation.setRole(role);
        conversation.setContent(content);
        conversation.setMessageType(messageType);
        conversationMapper.insert(conversation);
    }

    /**
     * 删除线程中最新的 assistant 消息（取消时清理已保存的回复）
     */
    private void deleteLatestAssistantMessage(String threadId) {
        List<Conversation> assistantMsgs = conversationMapper.selectList(
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getThreadId, threadId)
                        .eq(Conversation::getRole, "assistant")
                        .orderByDesc(Conversation::getCreatedAt)
                        .last("LIMIT 1"));
        if (!assistantMsgs.isEmpty()) {
            conversationMapper.deleteById(assistantMsgs.get(0).getId());
            log.info("已删除线程最新 assistant 消息: threadId={}, conversationId={}",
                    threadId, assistantMsgs.get(0).getId());
        }
    }

    /**
     * 如果线程标题还是默认值"新对话"，用第一条消息自动生成标题
     */
    private void autoGenerateTitle(ChatThread thread, String content) {
        if ("新对话".equals(thread.getTitle())) {
            String title = content.length() > TITLE_MAX_LENGTH
                    ? content.substring(0, TITLE_MAX_LENGTH) + "..."
                    : content;
            chatThreadService.updateTitle(thread.getThreadId(), title);
        }
    }
}
