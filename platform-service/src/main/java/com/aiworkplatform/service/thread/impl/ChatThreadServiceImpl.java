package com.aiworkplatform.service.thread.impl;

import com.aiworkplatform.domain.entity.ChatThread;
import com.aiworkplatform.domain.entity.Conversation;
import com.aiworkplatform.domain.mapper.ChatThreadMapper;
import com.aiworkplatform.domain.mapper.ConversationMapper;
import com.aiworkplatform.service.thread.ChatThreadService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ChatThreadServiceImpl implements ChatThreadService {

    private static final Logger log = LoggerFactory.getLogger(ChatThreadServiceImpl.class);

    private final ChatThreadMapper chatThreadMapper;
    private final ConversationMapper conversationMapper;

    public ChatThreadServiceImpl(ChatThreadMapper chatThreadMapper,
                                 ConversationMapper conversationMapper) {
        this.chatThreadMapper = chatThreadMapper;
        this.conversationMapper = conversationMapper;
    }

    @Override
    public synchronized ChatThread createThread(String projectId) {
        // 查找该项目最近创建的线程，如果没有对话内容则直接复用
        ChatThread latest = chatThreadMapper.selectOne(
                new LambdaQueryWrapper<ChatThread>()
                        .eq(ChatThread::getProjectId, projectId)
                        .orderByDesc(ChatThread::getCreatedAt)
                        .last("LIMIT 1"));

        if (latest != null) {
            Long msgCount = conversationMapper.selectCount(
                    new LambdaQueryWrapper<Conversation>()
                            .eq(Conversation::getThreadId, latest.getThreadId()));
            if (msgCount == 0) {
                log.info("复用空线程: projectId={}, threadId={}", projectId, latest.getThreadId());
                return latest;
            }
        }

        ChatThread thread = new ChatThread();
        thread.setThreadId(generateThreadId());
        thread.setProjectId(projectId);
        thread.setTitle("新对话");
        chatThreadMapper.insert(thread);

        log.info("创建对话线程: projectId={}, threadId={}", projectId, thread.getThreadId());
        return thread;
    }

    @Override
    public List<ChatThread> listByProject(String projectId) {
        return chatThreadMapper.selectList(
                new LambdaQueryWrapper<ChatThread>()
                        .eq(ChatThread::getProjectId, projectId)
                        .orderByDesc(ChatThread::getUpdatedAt));
    }

    @Override
    public ChatThread getByThreadId(String threadId) {
        ChatThread thread = chatThreadMapper.selectOne(
                new LambdaQueryWrapper<ChatThread>()
                        .eq(ChatThread::getThreadId, threadId));
        if (thread == null) {
            throw new RuntimeException("线程不存在: " + threadId);
        }
        return thread;
    }

    @Override
    public void updateTitle(String threadId, String title) {
        ChatThread thread = getByThreadId(threadId);
        thread.setTitle(title);
        chatThreadMapper.updateById(thread);

        log.info("更新线程标题: threadId={}, title={}", threadId, title);
    }

    @Override
    public void deleteThread(String threadId) {
        ChatThread thread = getByThreadId(threadId);

        // 逻辑删除线程
        chatThreadMapper.deleteById(thread.getId());

        // 逻辑删除该线程下的所有消息
        conversationMapper.update(null,
                new UpdateWrapper<Conversation>()
                        .eq("thread_id", threadId)
                        .set("deleted", 1));

        log.info("删除对话线程: threadId={}", threadId);
    }

    @Override
    public ChatThread getOrCreateDefaultThread(String projectId) {
        // 查找项目下最新的线程
        ChatThread latest = chatThreadMapper.selectOne(
                new LambdaQueryWrapper<ChatThread>()
                        .eq(ChatThread::getProjectId, projectId)
                        .orderByDesc(ChatThread::getUpdatedAt)
                        .last("LIMIT 1"));

        if (latest != null) {
            return latest;
        }

        // 没有线程则自动创建
        return createThread(projectId);
    }

    @Override
    public void updateClaudeSessionId(String threadId, String sessionId) {
        ChatThread thread = getByThreadId(threadId);
        thread.setClaudeSessionId(sessionId);
        chatThreadMapper.updateById(thread);
        log.info("更新 Claude session: threadId={}, sessionId={}", threadId, sessionId);
    }

    private String generateThreadId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
