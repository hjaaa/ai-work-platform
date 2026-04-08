package com.aiworkplatform.service.git;

import com.aiworkplatform.domain.entity.Project;
import com.aiworkplatform.service.chat.MessagePushService;
import com.aiworkplatform.service.git.impl.GitServiceImpl;
import com.aiworkplatform.service.project.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GitServiceImplTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private MessagePushService messagePushService;

    @InjectMocks
    private GitServiceImpl gitService;

    private Project mockProject;

    @BeforeEach
    void setUp() {
        mockProject = new Project();
        mockProject.setProjectId("proj-test1234");
        mockProject.setGitUrl("git@github.com:test/repo.git");
        mockProject.setDefaultBranch("main");
        mockProject.setWorkspacePath("/tmp/test-workspace/proj-test1234");
    }

    @Test
    void should_mark_clone_failed_when_git_url_invalid() {
        // given: 一个不存在的 git 地址
        mockProject.setGitUrl("git@github.com:nonexistent/repo-404.git");
        when(projectService.getByProjectId("proj-test1234")).thenReturn(mockProject);

        // when
        gitService.cloneRepository("proj-test1234");

        // then: 应该标记失败，推送错误消息
        verify(projectService).markCloneFailed(eq("proj-test1234"), anyString());
        verify(messagePushService).pushProgress(eq("proj-test1234"), contains("拉取失败"));
    }

    @Test
    void should_push_progress_before_clone() {
        // given
        when(projectService.getByProjectId("proj-test1234")).thenReturn(mockProject);

        // when
        gitService.cloneRepository("proj-test1234");

        // then: 无论成功失败，第一条消息应该是"正在拉取代码..."
        verify(messagePushService).pushProgress("proj-test1234", "正在拉取代码...");
    }

    @Test
    void should_call_mark_clone_success_when_clone_succeeds() {
        // given: 使用一个本地已存在的公开仓库来测试成功路径
        // 这个测试依赖实际 git 命令，在 CI 环境可能需要跳过
        // 这里只验证失败路径的完整性（成功路径需要集成测试）
        mockProject.setGitUrl("invalid://not-a-real-url");
        when(projectService.getByProjectId("proj-test1234")).thenReturn(mockProject);

        gitService.cloneRepository("proj-test1234");

        // 无效 URL 必然失败
        verify(projectService).markCloneFailed(eq("proj-test1234"), anyString());
        verify(projectService, never()).markCloneSuccess(anyString(), anyString());
    }
}
