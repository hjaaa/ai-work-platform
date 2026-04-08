package com.aiworkplatform.service.chat;

import com.aiworkplatform.domain.entity.ChatThread;
import com.aiworkplatform.domain.entity.Conversation;
import com.aiworkplatform.domain.entity.Project;
import com.aiworkplatform.domain.mapper.ConversationMapper;
import com.aiworkplatform.domain.mapper.GenerationMapper;
import com.aiworkplatform.service.chat.impl.ChatServiceImpl;
import com.aiworkplatform.service.orchestrator.ClaudeCodeOrchestrator;
import com.aiworkplatform.service.orchestrator.ExecutionResult;
import com.aiworkplatform.service.project.ProjectService;
import com.aiworkplatform.service.thread.ChatThreadService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatServiceImplTest {

    @Mock
    private ConversationMapper conversationMapper;
    @Mock
    private GenerationMapper generationMapper;
    @Mock
    private ProjectService projectService;
    @Mock
    private ChatThreadService chatThreadService;
    @Mock
    private ClaudeCodeOrchestrator orchestrator;
    @Mock
    private MessagePushService messagePushService;

    private ChatServiceImpl chatService;

    @BeforeEach
    void setUp() {
        chatService = new ChatServiceImpl(
                conversationMapper, generationMapper, projectService,
                chatThreadService, orchestrator, messagePushService);
    }

    // --- getHistoryByThread ---

    @Test
    void should_returnMessages_when_threadHasHistory() {
        Conversation c1 = new Conversation();
        c1.setThreadId("t1");
        c1.setContent("你好");

        when(conversationMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(c1));

        List<Conversation> result = chatService.getHistoryByThread("t1");

        assertEquals(1, result.size());
        assertEquals("你好", result.get(0).getContent());
    }

    @Test
    void should_returnEmpty_when_threadHasNoHistory() {
        when(conversationMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.emptyList());

        List<Conversation> result = chatService.getHistoryByThread("t-empty");

        assertTrue(result.isEmpty());
    }

    // --- handleThreadMessage ---

    @Test
    void should_saveMessageAndCallOrchestrator_when_handleThreadMessage() {
        ChatThread thread = new ChatThread();
        thread.setThreadId("t1");
        thread.setProjectId("proj-001");
        thread.setTitle("已有标题");
        thread.setClaudeSessionId("session-123");

        Project project = new Project();
        project.setProjectId("proj-001");
        project.setWorkspacePath("/tmp/workspace/proj-001");

        ExecutionResult execResult = new ExecutionResult("生成的代码", "session-123");

        when(chatThreadService.getByThreadId("t1")).thenReturn(thread);
        when(projectService.getByProjectId("proj-001")).thenReturn(project);
        when(orchestrator.execute(eq("t1"), any(), eq("创建用户管理页面"), eq("session-123")))
                .thenReturn(execResult);

        chatService.handleThreadMessage("t1", "创建用户管理页面");

        // 验证保存了用户消息和 AI 回复（共 2 次 insert）
        verify(conversationMapper, times(2)).insert(any(Conversation.class));
        // 验证调用了 orchestrator（session 模式）
        verify(orchestrator).execute(eq("t1"), any(), eq("创建用户管理页面"), eq("session-123"));
        // 验证推送了完成进度
        verify(messagePushService).pushProgressToThread(eq("t1"), eq("proj-001"), contains("完毕"));
    }

    @Test
    void should_saveSessionId_when_firstMessage() {
        ChatThread thread = new ChatThread();
        thread.setThreadId("t1");
        thread.setProjectId("proj-001");
        thread.setTitle("已有标题");
        thread.setClaudeSessionId(null); // 首次，无 session

        Project project = new Project();
        project.setProjectId("proj-001");
        project.setWorkspacePath("/tmp/workspace/proj-001");

        ExecutionResult execResult = new ExecutionResult("回复", "new-session-456");

        when(chatThreadService.getByThreadId("t1")).thenReturn(thread);
        when(projectService.getByProjectId("proj-001")).thenReturn(project);
        when(orchestrator.execute(eq("t1"), any(), anyString(), isNull()))
                .thenReturn(execResult);

        chatService.handleThreadMessage("t1", "你好");

        // 验证首次对话后保存了 session ID
        verify(chatThreadService).updateClaudeSessionId("t1", "new-session-456");
    }

    @Test
    void should_autoGenerateTitle_when_threadTitleIsDefault() {
        ChatThread thread = new ChatThread();
        thread.setThreadId("t1");
        thread.setProjectId("proj-001");
        thread.setTitle("新对话");

        Project project = new Project();
        project.setProjectId("proj-001");
        project.setWorkspacePath("/tmp/workspace/proj-001");

        when(chatThreadService.getByThreadId("t1")).thenReturn(thread);
        when(projectService.getByProjectId("proj-001")).thenReturn(project);
        when(orchestrator.execute(anyString(), any(), anyString(), any()))
                .thenReturn(new ExecutionResult("ok", "s1"));

        chatService.handleThreadMessage("t1", "创建一个用户管理页面");

        verify(chatThreadService).updateTitle("t1", "创建一个用户管理页面");
    }

    @Test
    void should_truncateTitle_when_messageIsTooLong() {
        ChatThread thread = new ChatThread();
        thread.setThreadId("t1");
        thread.setProjectId("proj-001");
        thread.setTitle("新对话");

        Project project = new Project();
        project.setProjectId("proj-001");
        project.setWorkspacePath("/tmp/workspace/proj-001");

        when(chatThreadService.getByThreadId("t1")).thenReturn(thread);
        when(projectService.getByProjectId("proj-001")).thenReturn(project);
        when(orchestrator.execute(anyString(), any(), anyString(), any()))
                .thenReturn(new ExecutionResult("ok", "s1"));

        String longMessage = "这是一条非常非常非常非常非常非常非常非常非常非常非常非常非常非常非常长的消息内容需要被截断";
        chatService.handleThreadMessage("t1", longMessage);

        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatThreadService).updateTitle(eq("t1"), titleCaptor.capture());
        assertTrue(titleCaptor.getValue().endsWith("..."));
        assertTrue(titleCaptor.getValue().length() <= 33);
    }

    @Test
    void should_notUpdateTitle_when_threadAlreadyHasCustomTitle() {
        ChatThread thread = new ChatThread();
        thread.setThreadId("t1");
        thread.setProjectId("proj-001");
        thread.setTitle("我的自定义标题");

        Project project = new Project();
        project.setProjectId("proj-001");
        project.setWorkspacePath("/tmp/workspace/proj-001");

        when(chatThreadService.getByThreadId("t1")).thenReturn(thread);
        when(projectService.getByProjectId("proj-001")).thenReturn(project);
        when(orchestrator.execute(anyString(), any(), anyString(), any()))
                .thenReturn(new ExecutionResult("ok", "s1"));

        chatService.handleThreadMessage("t1", "新消息");

        verify(chatThreadService, never()).updateTitle(anyString(), anyString());
    }

    @Test
    void should_pushErrorMessage_when_orchestratorFails() {
        ChatThread thread = new ChatThread();
        thread.setThreadId("t1");
        thread.setProjectId("proj-001");
        thread.setTitle("已有标题");

        Project project = new Project();
        project.setProjectId("proj-001");
        project.setWorkspacePath("/tmp/workspace/proj-001");

        when(chatThreadService.getByThreadId("t1")).thenReturn(thread);
        when(projectService.getByProjectId("proj-001")).thenReturn(project);
        when(orchestrator.execute(anyString(), any(), anyString(), any()))
                .thenThrow(new RuntimeException("CLI 超时"));

        chatService.handleThreadMessage("t1", "创建页面");

        verify(messagePushService).pushAssistantMessageToThread(
                eq("t1"), eq("proj-001"), contains("CLI 超时"));
    }

    // --- cancelThread ---

    @Test
    void should_delegateToOrchestrator_when_cancelThread() {
        when(orchestrator.cancel("t1")).thenReturn(true);

        chatService.cancelThread("t1");

        verify(orchestrator).cancel("t1");
    }

    @Test
    void should_pushCancelledAndNotSaveReply_when_orchestratorReturnsCancelled() {
        ChatThread thread = new ChatThread();
        thread.setThreadId("t1");
        thread.setProjectId("proj-001");
        thread.setTitle("已有标题");

        Project project = new Project();
        project.setProjectId("proj-001");
        project.setWorkspacePath("/tmp/workspace/proj-001");

        when(chatThreadService.getByThreadId("t1")).thenReturn(thread);
        when(projectService.getByProjectId("proj-001")).thenReturn(project);
        when(orchestrator.execute(anyString(), any(), anyString(), any()))
                .thenReturn(ExecutionResult.cancelled());

        chatService.handleThreadMessage("t1", "创建页面");

        // 验证只保存了用户消息（1 次 insert），没有保存 AI 回复
        verify(conversationMapper, times(1)).insert(any(Conversation.class));
        // 验证推送了取消进度
        verify(messagePushService).pushProgressToThread(eq("t1"), eq("proj-001"), contains("取消"));
        // 验证没有推送 AI 回复
        verify(messagePushService, never()).pushAssistantMessageToThread(anyString(), anyString(), anyString());
    }

    @Test
    void should_notUpdateSessionId_when_cancelledOnFirstMessage() {
        ChatThread thread = new ChatThread();
        thread.setThreadId("t1");
        thread.setProjectId("proj-001");
        thread.setTitle("已有标题");
        thread.setClaudeSessionId(null);

        Project project = new Project();
        project.setProjectId("proj-001");
        project.setWorkspacePath("/tmp/workspace/proj-001");

        when(chatThreadService.getByThreadId("t1")).thenReturn(thread);
        when(projectService.getByProjectId("proj-001")).thenReturn(project);
        when(orchestrator.execute(anyString(), any(), anyString(), isNull()))
                .thenReturn(ExecutionResult.cancelled());

        chatService.handleThreadMessage("t1", "你好");

        // 取消时不应保存 session ID
        verify(chatThreadService, never()).updateClaudeSessionId(anyString(), anyString());
    }

    // --- handleUserMessage (旧接口兼容) ---

    @Test
    void should_routeToDefaultThread_when_usingDeprecatedApi() {
        ChatThread defaultThread = new ChatThread();
        defaultThread.setThreadId("t-default");
        defaultThread.setProjectId("proj-001");
        defaultThread.setTitle("已有标题");

        Project project = new Project();
        project.setProjectId("proj-001");
        project.setWorkspacePath("/tmp/workspace/proj-001");

        when(chatThreadService.getOrCreateDefaultThread("proj-001")).thenReturn(defaultThread);
        when(chatThreadService.getByThreadId("t-default")).thenReturn(defaultThread);
        when(projectService.getByProjectId("proj-001")).thenReturn(project);
        when(orchestrator.execute(anyString(), any(), anyString(), any()))
                .thenReturn(new ExecutionResult("result", "s1"));

        chatService.handleUserMessage("proj-001", "测试消息");

        verify(chatThreadService).getOrCreateDefaultThread("proj-001");
        verify(chatThreadService).getByThreadId("t-default");
    }
}
