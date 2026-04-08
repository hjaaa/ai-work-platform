package com.aiworkplatform.service.thread;

import com.aiworkplatform.domain.entity.ChatThread;
import com.aiworkplatform.domain.mapper.ChatThreadMapper;
import com.aiworkplatform.domain.mapper.ConversationMapper;
import com.aiworkplatform.service.thread.impl.ChatThreadServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatThreadServiceImplTest {

    @Mock
    private ChatThreadMapper chatThreadMapper;

    @Mock
    private ConversationMapper conversationMapper;

    private ChatThreadServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ChatThreadServiceImpl(chatThreadMapper, conversationMapper);
    }

    // --- createThread ---

    @Test
    void should_createThread_when_noExistingThread() {
        when(chatThreadMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(chatThreadMapper.insert(any(ChatThread.class))).thenAnswer(inv -> 1);

        ChatThread thread = service.createThread("proj-001");

        assertNotNull(thread);
        assertEquals("proj-001", thread.getProjectId());
        assertEquals("新对话", thread.getTitle());
        assertNotNull(thread.getThreadId());
        assertTrue(thread.getThreadId().length() > 0);

        verify(chatThreadMapper).insert(any(ChatThread.class));
    }

    @Test
    void should_reuseThread_when_latestThreadHasNoMessages() {
        ChatThread emptyThread = new ChatThread();
        emptyThread.setThreadId("t-empty");
        emptyThread.setProjectId("proj-001");

        when(chatThreadMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(emptyThread);
        when(conversationMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        ChatThread result = service.createThread("proj-001");

        assertEquals("t-empty", result.getThreadId());
        verify(chatThreadMapper, never()).insert(any(ChatThread.class));
    }

    @Test
    void should_createNewThread_when_latestThreadHasMessages() {
        ChatThread existingThread = new ChatThread();
        existingThread.setThreadId("t-with-msg");
        existingThread.setProjectId("proj-001");

        when(chatThreadMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingThread);
        when(conversationMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(3L);
        when(chatThreadMapper.insert(any(ChatThread.class))).thenAnswer(inv -> 1);

        ChatThread result = service.createThread("proj-001");

        assertNotEquals("t-with-msg", result.getThreadId());
        assertEquals("新对话", result.getTitle());
        verify(chatThreadMapper).insert(any(ChatThread.class));
    }

    // --- listByProject ---

    @Test
    void should_returnThreadsList_when_projectHasThreads() {
        ChatThread t1 = new ChatThread();
        t1.setThreadId("t1");
        t1.setProjectId("proj-001");
        ChatThread t2 = new ChatThread();
        t2.setThreadId("t2");
        t2.setProjectId("proj-001");

        when(chatThreadMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(t1, t2));

        List<ChatThread> result = service.listByProject("proj-001");

        assertEquals(2, result.size());
        verify(chatThreadMapper).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    void should_returnEmptyList_when_projectHasNoThreads() {
        when(chatThreadMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());

        List<ChatThread> result = service.listByProject("proj-empty");

        assertTrue(result.isEmpty());
    }

    // --- getByThreadId ---

    @Test
    void should_returnThread_when_threadExists() {
        ChatThread thread = new ChatThread();
        thread.setThreadId("t1");
        thread.setProjectId("proj-001");

        when(chatThreadMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(thread);

        ChatThread result = service.getByThreadId("t1");

        assertNotNull(result);
        assertEquals("t1", result.getThreadId());
    }

    @Test
    void should_throwException_when_threadNotFound() {
        when(chatThreadMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThrows(RuntimeException.class, () -> service.getByThreadId("not-exist"));
    }

    // --- updateTitle ---

    @Test
    void should_updateTitle_when_validInput() {
        ChatThread thread = new ChatThread();
        thread.setId(1L);
        thread.setThreadId("t1");

        when(chatThreadMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(thread);
        when(chatThreadMapper.updateById(any(ChatThread.class))).thenReturn(1);

        service.updateTitle("t1", "新标题");

        ArgumentCaptor<ChatThread> captor = ArgumentCaptor.forClass(ChatThread.class);
        verify(chatThreadMapper).updateById(captor.capture());
        assertEquals("新标题", captor.getValue().getTitle());
    }

    // --- deleteThread ---

    @Test
    void should_deleteThreadAndMessages_when_threadExists() {
        ChatThread thread = new ChatThread();
        thread.setId(1L);
        thread.setThreadId("t1");

        when(chatThreadMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(thread);
        when(chatThreadMapper.deleteById(1L)).thenReturn(1);
        when(conversationMapper.update(any(), any(UpdateWrapper.class))).thenReturn(3);

        service.deleteThread("t1");

        // 逻辑删除线程
        verify(chatThreadMapper).deleteById(1L);
        // 逻辑删除关联消息
        verify(conversationMapper).update(any(), any(UpdateWrapper.class));
    }

    // --- getOrCreateDefaultThread ---

    @Test
    void should_returnExistingThread_when_projectHasThreads() {
        ChatThread existing = new ChatThread();
        existing.setThreadId("t1");
        existing.setProjectId("proj-001");

        when(chatThreadMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        ChatThread result = service.getOrCreateDefaultThread("proj-001");

        assertEquals("t1", result.getThreadId());
        verify(chatThreadMapper, never()).insert(any(ChatThread.class));
    }

    @Test
    void should_createNewThread_when_projectHasNoThreads() {
        when(chatThreadMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(chatThreadMapper.insert(any(ChatThread.class))).thenAnswer(inv -> 1);

        ChatThread result = service.getOrCreateDefaultThread("proj-new");

        assertNotNull(result);
        assertEquals("proj-new", result.getProjectId());
        verify(chatThreadMapper).insert(any(ChatThread.class));
    }
}
