package com.aiworkplatform.service.spec;

import com.aiworkplatform.common.exception.BusinessException;
import com.aiworkplatform.domain.entity.DevSpec;
import com.aiworkplatform.domain.entity.Project;
import com.aiworkplatform.domain.entity.SpecProjectLink;
import com.aiworkplatform.domain.mapper.DevSpecMapper;
import com.aiworkplatform.domain.mapper.ProjectMapper;
import com.aiworkplatform.domain.mapper.SpecProjectLinkMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SpecServiceImplTest {

    @Mock
    private DevSpecMapper devSpecMapper;

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private SpecProjectLinkMapper specProjectLinkMapper;

    @InjectMocks
    private com.aiworkplatform.service.spec.impl.SpecServiceImpl specService;

    @TempDir
    Path tempDir;

    // ======================== createSpec ========================

    @Test
    void should_createSpec_successfully() {
        when(devSpecMapper.insert(any(DevSpec.class))).thenReturn(1);

        DevSpec result = specService.createSpec("API 设计规范", "BACKEND", "## 规范内容", "user1");

        assertNotNull(result.getSpecId());
        assertEquals("API 设计规范", result.getName());
        assertEquals("BACKEND", result.getSpecType());
        assertEquals("## 规范内容", result.getContent());
        assertEquals("user1", result.getCreatedBy());
        verify(devSpecMapper).insert(any(DevSpec.class));
    }

    @Test
    void should_throwException_when_createSpec_with_blank_name() {
        assertThrows(BusinessException.class,
                () -> specService.createSpec("", "BACKEND", "content", "user1"));
    }

    @Test
    void should_throwException_when_createSpec_with_invalid_type() {
        assertThrows(BusinessException.class,
                () -> specService.createSpec("规范", "INVALID", "content", "user1"));
    }

    // ======================== updateSpec ========================

    @Test
    void should_updateSpec_successfully() {
        DevSpec spec = buildSpec("spec001");
        when(devSpecMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(spec);
        when(devSpecMapper.updateById(any(DevSpec.class))).thenReturn(1);

        specService.updateSpec("spec001", "新名称", "FRONTEND", "新内容");

        ArgumentCaptor<DevSpec> captor = ArgumentCaptor.forClass(DevSpec.class);
        verify(devSpecMapper).updateById(captor.capture());
        assertEquals("新名称", captor.getValue().getName());
        assertEquals("FRONTEND", captor.getValue().getSpecType());
        assertEquals("新内容", captor.getValue().getContent());
    }

    @Test
    void should_throwException_when_updateSpec_not_found() {
        when(devSpecMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThrows(BusinessException.class,
                () -> specService.updateSpec("not-exist", "名称", "UI", "内容"));
    }

    // ======================== deleteSpec ========================

    @Test
    void should_throwException_when_deleteSpec_with_existing_links() {
        DevSpec spec = buildSpec("spec001");
        when(devSpecMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(spec);
        when(specProjectLinkMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(2L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> specService.deleteSpec("spec001"));
        assertTrue(ex.getMessage().contains("关联"));
    }

    @Test
    void should_deleteSpec_successfully_when_no_links() {
        DevSpec spec = buildSpec("spec001");
        when(devSpecMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(spec);
        when(specProjectLinkMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(devSpecMapper.deleteById(any(DevSpec.class))).thenReturn(1);

        specService.deleteSpec("spec001");

        verify(devSpecMapper).deleteById(spec);
    }

    // ======================== linkToProject ========================

    @Test
    void should_linkToProject_successfully() throws IOException {
        DevSpec spec = buildSpec("spec001");
        spec.setContent("## 规范");
        Project project = buildProject("proj001", tempDir.toString());

        when(devSpecMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(spec);
        when(projectMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(project);
        // 尚未关联
        when(specProjectLinkMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(specProjectLinkMapper.insert(any(SpecProjectLink.class))).thenReturn(1);

        specService.linkToProject("spec001", "proj001");

        // 验证磁盘文件写入
        Path rulesFile = tempDir.resolve("rules/spec001.md");
        assertTrue(Files.exists(rulesFile));
        assertEquals("## 规范", Files.readString(rulesFile));

        // 验证 CLAUDE.md 写入
        Path claudeMd = tempDir.resolve("CLAUDE.md");
        assertTrue(Files.exists(claudeMd));
        String claudeContent = Files.readString(claudeMd);
        assertTrue(claudeContent.contains("rules/spec001.md"));

        // 验证 DB 插入
        verify(specProjectLinkMapper).insert(any(SpecProjectLink.class));
    }

    @Test
    void should_throwException_when_linkToProject_codePath_empty() {
        DevSpec spec = buildSpec("spec001");
        Project project = buildProject("proj001", "");

        when(devSpecMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(spec);
        when(projectMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(project);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> specService.linkToProject("spec001", "proj001"));
        assertTrue(ex.getMessage().contains("codePath"));
    }

    @Test
    void should_linkToProject_idempotent_when_already_linked() {
        DevSpec spec = buildSpec("spec001");
        Project project = buildProject("proj001", tempDir.toString());
        SpecProjectLink existingLink = new SpecProjectLink();
        existingLink.setSpecId("spec001");
        existingLink.setProjectId("proj001");

        when(devSpecMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(spec);
        when(projectMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(project);
        when(specProjectLinkMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingLink);

        // 幂等：不抛异常，不重复插入
        specService.linkToProject("spec001", "proj001");

        verify(specProjectLinkMapper, never()).insert(any(SpecProjectLink.class));
    }

    // ======================== unlinkFromProject ========================

    @Test
    void should_unlinkFromProject_successfully() throws IOException {
        DevSpec spec = buildSpec("spec001");
        spec.setName("API规范");
        Project project = buildProject("proj001", tempDir.toString());

        // 预先创建磁盘文件
        Path rulesDir = tempDir.resolve("rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("spec001.md"), "内容");

        // 预先写 CLAUDE.md
        Path claudeMd = tempDir.resolve("CLAUDE.md");
        Files.writeString(claudeMd, "# 已有内容\n\n## 开发规范\n- rules/spec001.md：API规范\n");

        SpecProjectLink link = new SpecProjectLink();
        link.setSpecId("spec001");
        link.setProjectId("proj001");

        when(devSpecMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(spec);
        when(projectMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(project);
        when(specProjectLinkMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(link);

        specService.unlinkFromProject("spec001", "proj001");

        // 文件已删除
        assertFalse(Files.exists(rulesDir.resolve("spec001.md")));
        // link 记录已删除
        verify(specProjectLinkMapper).delete(any(LambdaQueryWrapper.class));
    }

    @Test
    void should_unlinkFromProject_silently_when_not_linked() {
        DevSpec spec = buildSpec("spec001");
        Project project = buildProject("proj001", tempDir.toString());

        when(devSpecMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(spec);
        when(projectMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(project);
        when(specProjectLinkMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        // 不抛异常
        assertDoesNotThrow(() -> specService.unlinkFromProject("spec001", "proj001"));
        verify(specProjectLinkMapper, never()).delete(any(LambdaQueryWrapper.class));
    }

    // ======================== 工具方法 ========================

    private DevSpec buildSpec(String specId) {
        DevSpec spec = new DevSpec();
        spec.setSpecId(specId);
        spec.setName("测试规范");
        spec.setSpecType("BACKEND");
        spec.setContent("## 规范内容");
        return spec;
    }

    private Project buildProject(String projectId, String codePath) {
        Project project = new Project();
        project.setProjectId(projectId);
        project.setName("测试项目");
        project.setCodePath(codePath);
        return project;
    }
}
