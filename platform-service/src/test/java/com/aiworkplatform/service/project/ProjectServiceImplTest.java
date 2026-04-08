package com.aiworkplatform.service.project;

import com.aiworkplatform.common.exception.BusinessException;
import com.aiworkplatform.domain.entity.Project;
import com.aiworkplatform.domain.enums.ProjectStatus;
import com.aiworkplatform.domain.mapper.ProjectMapper;
import com.aiworkplatform.service.project.impl.ProjectServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectServiceImplTest {

    @Mock
    private ProjectMapper projectMapper;

    @InjectMocks
    private ProjectServiceImpl projectService;

    @TempDir
    Path tempDir;

    @Test
    void should_create_project_with_git_fields() {
        // given
        when(projectMapper.insert(any(Project.class))).thenReturn(1);

        // when
        Project result = projectService.createProject(
                "测试项目", "git@github.com:test/repo.git", "develop",
                "描述", tempDir.toString(), "user1");

        // then
        assertNotNull(result.getProjectId());
        assertTrue(result.getProjectId().startsWith("proj-"));
        assertEquals("测试项目", result.getName());
        assertEquals("git@github.com:test/repo.git", result.getGitUrl());
        assertEquals("develop", result.getDefaultBranch());
        assertEquals("描述", result.getDescription());
        assertEquals(ProjectStatus.CREATING.getValue(), result.getStatus());
        assertEquals("user1", result.getCreatedBy());
        verify(projectMapper).insert(any(Project.class));
    }

    @Test
    void should_default_branch_to_main_when_blank() {
        // given
        when(projectMapper.insert(any(Project.class))).thenReturn(1);

        // when
        Project result = projectService.createProject(
                "测试项目", "git@github.com:test/repo.git", "",
                null, tempDir.toString(), "user1");

        // then
        assertEquals("main", result.getDefaultBranch());
    }

    @Test
    void should_default_branch_to_main_when_null() {
        // given
        when(projectMapper.insert(any(Project.class))).thenReturn(1);

        // when
        Project result = projectService.createProject(
                "测试项目", "git@github.com:test/repo.git", null,
                null, tempDir.toString(), "user1");

        // then
        assertEquals("main", result.getDefaultBranch());
    }

    @Test
    void should_mark_clone_success() {
        // given
        Project project = new Project();
        project.setProjectId("proj-abc");
        project.setStatus(ProjectStatus.CREATING.getValue());
        when(projectMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(project);

        // when
        projectService.markCloneSuccess("proj-abc", "/path/to/code");

        // then
        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectMapper).updateById(captor.capture());
        Project updated = captor.getValue();
        assertEquals("/path/to/code", updated.getCodePath());
        assertEquals(ProjectStatus.ACTIVE.getValue(), updated.getStatus());
        assertNull(updated.getCloneErrorMessage());
    }

    @Test
    void should_mark_clone_failed_with_error_message() {
        // given
        Project project = new Project();
        project.setProjectId("proj-abc");
        project.setStatus(ProjectStatus.CREATING.getValue());
        when(projectMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(project);

        // when
        projectService.markCloneFailed("proj-abc", "认证失败: Permission denied");

        // then
        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectMapper).updateById(captor.capture());
        Project updated = captor.getValue();
        assertEquals(ProjectStatus.FAILED.getValue(), updated.getStatus());
        assertEquals("认证失败: Permission denied", updated.getCloneErrorMessage());
    }

    @Test
    void should_throw_when_project_not_found() {
        // given
        when(projectMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        // when & then
        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.getByProjectId("proj-nonexist"));
        assertTrue(ex.getMessage().contains("项目不存在"));
    }
}
