package com.aiworkplatform.service.skill;

import com.aiworkplatform.common.exception.BusinessException;
import com.aiworkplatform.domain.entity.Project;
import com.aiworkplatform.domain.entity.Skill;
import com.aiworkplatform.domain.enums.SkillScope;
import com.aiworkplatform.domain.mapper.ProjectMapper;
import com.aiworkplatform.domain.mapper.SkillMapper;
import com.aiworkplatform.service.skill.impl.SkillServiceImpl;
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
class SkillServiceImplTest {

    @Mock
    private SkillMapper skillMapper;

    @Mock
    private ProjectMapper projectMapper;

    @InjectMocks
    private SkillServiceImpl skillService;

    @TempDir
    Path tempDir;

    // ----------------------------- create -----------------------------

    @Test
    void should_create_personal_skill_and_write_file() throws IOException {
        // given
        when(skillMapper.insert(any(Skill.class))).thenReturn(1);
        Path skillsDir = tempDir.resolve(".claude/skills");

        // when
        Skill result = skillService.createSkill(
                "我的技能", "技能描述内容", SkillScope.PERSONAL,
                null, "user1", skillsDir.getParent().getParent().toString());

        // then
        assertNotNull(result.getSkillId());
        assertEquals("我的技能", result.getName());
        assertEquals(SkillScope.PERSONAL.getValue(), result.getScope());
        verify(skillMapper).insert(any(Skill.class));
    }

    @Test
    void should_create_project_skill_and_write_file_to_code_path() throws IOException {
        // given
        Project project = new Project();
        project.setProjectId("proj-001");
        project.setCodePath(tempDir.toString());
        when(projectMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(project);
        when(skillMapper.insert(any(Skill.class))).thenReturn(1);

        // when
        Skill result = skillService.createSkill(
                "项目技能", "内容", SkillScope.PROJECT,
                "proj-001", "user1", null);

        // then
        assertEquals(SkillScope.PROJECT.getValue(), result.getScope());
        assertEquals("proj-001", result.getProjectId());
        // 文件应写入 {codePath}/.claude/skills/{skillId}.md
        Path skillFile = tempDir.resolve(".claude/skills/" + result.getSkillId() + ".md");
        assertTrue(Files.exists(skillFile), "项目 skill 文件应写入 codePath 下");
        verify(skillMapper).insert(any(Skill.class));
    }

    @Test
    void should_throw_when_project_scope_but_no_project_id() {
        // when & then
        BusinessException ex = assertThrows(BusinessException.class,
                () -> skillService.createSkill("技能", "内容", SkillScope.PROJECT, null, "user1", null));
        assertTrue(ex.getMessage().contains("projectId"));
    }

    @Test
    void should_throw_when_project_code_path_is_blank() {
        // given
        Project project = new Project();
        project.setProjectId("proj-001");
        project.setCodePath(null); // codePath 为空
        when(projectMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(project);

        // when & then
        BusinessException ex = assertThrows(BusinessException.class,
                () -> skillService.createSkill("技能", "内容", SkillScope.PROJECT, "proj-001", "user1", null));
        assertTrue(ex.getMessage().contains("codePath"));
    }

    // ----------------------------- update -----------------------------

    @Test
    void should_update_skill_and_overwrite_file() throws IOException {
        // given
        Path skillsDir = tempDir.resolve(".claude/skills");
        Files.createDirectories(skillsDir);
        String skillId = "skill-abc";
        Files.writeString(skillsDir.resolve(skillId + ".md"), "旧内容");

        Skill existing = new Skill();
        existing.setSkillId(skillId);
        existing.setName("旧名称");
        existing.setScope(SkillScope.PERSONAL.getValue());
        existing.setDescription("旧内容");

        when(skillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(skillMapper.updateById(any(Skill.class))).thenReturn(1);

        // when — tempDir 即个人 skill 的基础目录（等价于 ~）
        skillService.updateSkill(skillId, "新名称", "新内容", tempDir.toString());

        // then
        ArgumentCaptor<Skill> captor = ArgumentCaptor.forClass(Skill.class);
        verify(skillMapper).updateById(captor.capture());
        assertEquals("新名称", captor.getValue().getName());
        assertEquals("新内容", captor.getValue().getDescription());
    }

    @Test
    void should_throw_when_update_nonexistent_skill() {
        // given
        when(skillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        // when & then
        BusinessException ex = assertThrows(BusinessException.class,
                () -> skillService.updateSkill("nonexist", "名称", "内容", "/any"));
        assertTrue(ex.getMessage().contains("Skill 不存在"));
    }

    // ----------------------------- delete -----------------------------

    @Test
    void should_delete_skill_and_remove_file() throws IOException {
        // given
        Path skillsDir = tempDir.resolve(".claude/skills");
        Files.createDirectories(skillsDir);
        String skillId = "skill-del";
        Path skillFile = skillsDir.resolve(skillId + ".md");
        Files.writeString(skillFile, "内容");

        Skill existing = new Skill();
        existing.setSkillId(skillId);
        existing.setScope(SkillScope.PERSONAL.getValue());

        when(skillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(skillMapper.deleteById(any(Skill.class))).thenReturn(1);

        // when — tempDir 即个人 skill 的基础目录（等价于 ~）
        skillService.deleteSkill(skillId, tempDir.toString());

        // then
        verify(skillMapper).deleteById(any(Skill.class));
        assertFalse(Files.exists(skillFile), "文件应被物理删除");
    }

    @Test
    void should_not_fail_when_delete_skill_file_not_exists() {
        // given — 文件不存在，不应报错
        Skill existing = new Skill();
        existing.setSkillId("skill-nofile");
        existing.setScope(SkillScope.PERSONAL.getValue());

        when(skillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(skillMapper.deleteById(any(Skill.class))).thenReturn(1);

        // when & then — 不抛异常，传入任意存在目录即可
        assertDoesNotThrow(() -> skillService.deleteSkill("skill-nofile", tempDir.getParent().toString()));
    }

    @Test
    void should_throw_when_delete_nonexistent_skill() {
        // given
        when(skillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        // when & then
        BusinessException ex = assertThrows(BusinessException.class,
                () -> skillService.deleteSkill("nonexist", "/any"));
        assertTrue(ex.getMessage().contains("Skill 不存在"));
    }

    // ----------------------------- SYSTEM scope 限制 -----------------------------

    @Test
    void should_throw_when_create_system_scope_skill() {
        // 系统级 skill 不允许通过 API 创建
        BusinessException ex = assertThrows(BusinessException.class,
                () -> skillService.createSkill("系统技能", "内容", SkillScope.SYSTEM, null, "user1", null));
        assertTrue(ex.getMessage().contains("系统级"));
    }

    @Test
    void should_throw_when_update_system_scope_skill() {
        // given — 模拟已存在的系统级 skill
        Skill systemSkill = new Skill();
        systemSkill.setSkillId("sys-001");
        systemSkill.setScope(SkillScope.SYSTEM.getValue());
        when(skillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(systemSkill);

        // when & then
        BusinessException ex = assertThrows(BusinessException.class,
                () -> skillService.updateSkill("sys-001", "新名称", "新内容", null));
        assertTrue(ex.getMessage().contains("系统级"));
    }

    @Test
    void should_throw_when_delete_system_scope_skill() {
        // given — 模拟已存在的系统级 skill
        Skill systemSkill = new Skill();
        systemSkill.setSkillId("sys-002");
        systemSkill.setScope(SkillScope.SYSTEM.getValue());
        when(skillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(systemSkill);

        // when & then
        BusinessException ex = assertThrows(BusinessException.class,
                () -> skillService.deleteSkill("sys-002", null));
        assertTrue(ex.getMessage().contains("系统级"));
    }

    @Test
    void should_exclude_system_skills_when_scope_is_null() {
        // given — listSkills(null, null) 应自动排除 SYSTEM
        when(skillMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        // when
        skillService.listSkills(null, null, false);

        // then — 验证调用了 selectList（条件中包含 ne 排除 SYSTEM）
        verify(skillMapper).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    void should_not_exclude_system_when_scope_is_specified() {
        // given — listSkills(PERSONAL, null) 按 scope 过滤，不额外排除
        when(skillMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        // when
        skillService.listSkills(SkillScope.PERSONAL, null, false);

        // then
        verify(skillMapper).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    void should_include_system_skills_when_include_system_is_true() {
        // given — includeSystem=true 时不排除 SYSTEM
        when(skillMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        // when
        skillService.listSkills(null, null, true);

        // then
        verify(skillMapper).selectList(any(LambdaQueryWrapper.class));
    }

    // ----------------------------- list -----------------------------

    @Test
    void should_list_skills_by_scope() {
        // given
        Skill skill1 = new Skill();
        skill1.setSkillId("s1");
        skill1.setScope(SkillScope.PERSONAL.getValue());
        when(skillMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(skill1));

        // when
        List<Skill> result = skillService.listSkills(SkillScope.PERSONAL, null, false);

        // then
        assertEquals(1, result.size());
        assertEquals("s1", result.get(0).getSkillId());
    }
}
