package com.aiworkplatform.service.skill.impl;

import com.aiworkplatform.common.exception.BusinessException;
import com.aiworkplatform.domain.entity.Project;
import com.aiworkplatform.domain.entity.Skill;
import com.aiworkplatform.domain.enums.SkillScope;
import com.aiworkplatform.domain.mapper.ProjectMapper;
import com.aiworkplatform.domain.mapper.SkillMapper;
import com.aiworkplatform.service.skill.SkillService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@Service
public class SkillServiceImpl implements SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillServiceImpl.class);

    private final SkillMapper skillMapper;
    private final ProjectMapper projectMapper;

    public SkillServiceImpl(SkillMapper skillMapper, ProjectMapper projectMapper) {
        this.skillMapper = skillMapper;
        this.projectMapper = projectMapper;
    }

    @Override
    @Transactional
    public Skill createSkill(String name, String description, SkillScope scope,
                             String projectId, String createdBy, String personalSkillBaseDir) {
        // 系统级 skill 不允许通过 API 创建
        if (SkillScope.SYSTEM == scope) {
            throw new BusinessException("系统级 Skill 不允许手动创建");
        }

        // 校验项目级 skill 的必填字段
        if (SkillScope.PROJECT == scope) {
            if (!StringUtils.hasText(projectId)) {
                throw new BusinessException("scope=PROJECT 时 projectId 不能为空");
            }
        }

        // 确定磁盘写入路径
        Path skillFilePath = resolveSkillFilePath(scope, projectId, null, personalSkillBaseDir);

        // 生成业务主键
        String skillId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        // 文件操作先于 DB，文件失败直接抛异常
        writeSkillFile(skillFilePath.getParent(), skillId, description);

        // 插入数据库
        Skill skill = new Skill();
        skill.setSkillId(skillId);
        skill.setName(name);
        skill.setDescription(description);
        skill.setScope(scope.getValue());
        skill.setProjectId(SkillScope.PROJECT == scope ? projectId : null);
        skill.setCreatedBy(createdBy);
        skillMapper.insert(skill);

        log.info("创建 Skill 成功: skillId={}, scope={}, projectId={}", skillId, scope, projectId);
        return skill;
    }

    @Override
    @Transactional
    public void updateSkill(String skillId, String name, String description, String personalSkillBaseDir) {
        Skill skill = getSkillOrThrow(skillId);

        // 系统级 skill 不允许修改
        if (SkillScope.SYSTEM.getValue().equals(skill.getScope())) {
            throw new BusinessException("系统级 Skill 不允许修改");
        }

        // 确定磁盘路径
        Path skillFileDir = resolveSkillFilePath(
                SkillScope.valueOf(skill.getScope()), skill.getProjectId(), skillId, personalSkillBaseDir
        ).getParent();

        // 先更新文件，失败则回滚
        writeSkillFile(skillFileDir, skillId, description);

        // 更新数据库
        skill.setName(name);
        skill.setDescription(description);
        skillMapper.updateById(skill);

        log.info("更新 Skill 成功: skillId={}", skillId);
    }

    @Override
    @Transactional
    public void deleteSkill(String skillId, String personalSkillBaseDir) {
        Skill skill = getSkillOrThrow(skillId);

        // 系统级 skill 不允许删除
        if (SkillScope.SYSTEM.getValue().equals(skill.getScope())) {
            throw new BusinessException("系统级 Skill 不允许删除");
        }

        // 确定文件路径并物理删除（不存在则静默忽略）
        Path skillFilePath = resolveSkillFilePath(
                SkillScope.valueOf(skill.getScope()), skill.getProjectId(), skillId, personalSkillBaseDir
        );
        deleteSkillFileIfExists(skillFilePath);

        // DB 软删除
        skillMapper.deleteById(skill);

        log.info("删除 Skill 成功: skillId={}", skillId);
    }

    @Override
    public List<Skill> listSkills(SkillScope scope, String projectId, boolean includeSystem) {
        LambdaQueryWrapper<Skill> wrapper = new LambdaQueryWrapper<>();
        if (scope != null) {
            wrapper.eq(Skill::getScope, scope.getValue());
        } else if (!includeSystem) {
            // 未指定 scope 且不包含系统级时，排除 SYSTEM
            wrapper.ne(Skill::getScope, SkillScope.SYSTEM.getValue());
        }
        if (StringUtils.hasText(projectId)) {
            wrapper.eq(Skill::getProjectId, projectId);
        }
        return skillMapper.selectList(wrapper);
    }

    // ----------------------------- 私有方法 -----------------------------

    /**
     * 解析 skill 文件完整路径：{baseDir}/.claude/skills/{skillId}.md
     * <p>
     * scope=PERSONAL：baseDir = personalSkillBaseDir（即用户主目录）
     * scope=PROJECT：baseDir = project.codePath
     */
    private Path resolveSkillFilePath(SkillScope scope, String projectId,
                                      String skillId, String personalSkillBaseDir) {
        Path baseDir;
        if (SkillScope.PERSONAL == scope) {
            String homeDir = StringUtils.hasText(personalSkillBaseDir)
                    ? personalSkillBaseDir
                    : System.getProperty("user.home");
            baseDir = Path.of(homeDir);
        } else {
            Project project = projectMapper.selectOne(
                    new LambdaQueryWrapper<Project>().eq(Project::getProjectId, projectId));
            if (project == null) {
                throw new BusinessException("项目不存在: " + projectId);
            }
            if (!StringUtils.hasText(project.getCodePath())) {
                throw new BusinessException("项目 codePath 为空，无法创建项目级 Skill（请确认项目已完成初始化）");
            }
            baseDir = Path.of(project.getCodePath());
        }
        String fileId = StringUtils.hasText(skillId) ? skillId : "placeholder";
        return baseDir.resolve(".claude/skills/" + fileId + ".md");
    }

    /** 写入 skill 文件，目录不存在时自动创建 */
    private void writeSkillFile(Path skillsDir, String skillId, String content) {
        try {
            Files.createDirectories(skillsDir);
            Files.writeString(skillsDir.resolve(skillId + ".md"),
                    content != null ? content : "");
        } catch (IOException e) {
            log.error("写入 Skill 文件失败: dir={}, skillId={}", skillsDir, skillId, e);
            throw new BusinessException("写入 Skill 文件失败: " + e.getMessage());
        }
    }

    /** 物理删除 skill 文件，文件不存在时静默忽略 */
    private void deleteSkillFileIfExists(Path skillFilePath) {
        try {
            Files.deleteIfExists(skillFilePath);
        } catch (IOException e) {
            log.warn("删除 Skill 文件失败（忽略）: path={}", skillFilePath, e);
        }
    }

    private Skill getSkillOrThrow(String skillId) {
        Skill skill = skillMapper.selectOne(
                new LambdaQueryWrapper<Skill>().eq(Skill::getSkillId, skillId));
        if (skill == null) {
            throw new BusinessException("Skill 不存在: " + skillId);
        }
        return skill;
    }
}
