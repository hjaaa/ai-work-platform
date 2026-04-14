package com.aiworkplatform.service.spec.impl;

import com.aiworkplatform.common.exception.BusinessException;
import com.aiworkplatform.domain.entity.DevSpec;
import com.aiworkplatform.domain.entity.Project;
import com.aiworkplatform.domain.entity.SpecProjectLink;
import com.aiworkplatform.domain.mapper.DevSpecMapper;
import com.aiworkplatform.domain.mapper.ProjectMapper;
import com.aiworkplatform.domain.mapper.SpecProjectLinkMapper;
import com.aiworkplatform.service.spec.SpecService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class SpecServiceImpl implements SpecService {

    private static final Logger log = LoggerFactory.getLogger(SpecServiceImpl.class);

    /** 允许的规范类型 */
    private static final Set<String> VALID_SPEC_TYPES = Set.of("UI", "FRONTEND", "BACKEND");

    private final DevSpecMapper devSpecMapper;
    private final ProjectMapper projectMapper;
    private final SpecProjectLinkMapper specProjectLinkMapper;

    public SpecServiceImpl(DevSpecMapper devSpecMapper,
                           ProjectMapper projectMapper,
                           SpecProjectLinkMapper specProjectLinkMapper) {
        this.devSpecMapper = devSpecMapper;
        this.projectMapper = projectMapper;
        this.specProjectLinkMapper = specProjectLinkMapper;
    }

    @Override
    @Transactional
    public DevSpec createSpec(String name, String specType, String content, String createdBy) {
        if (!StringUtils.hasText(name)) {
            throw new BusinessException("规范名称不能为空");
        }
        if (!VALID_SPEC_TYPES.contains(specType)) {
            throw new BusinessException("规范类型无效，允许值：UI / FRONTEND / BACKEND");
        }

        String specId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        DevSpec spec = new DevSpec();
        spec.setSpecId(specId);
        spec.setName(name);
        spec.setSpecType(specType);
        spec.setContent(content);
        spec.setCreatedBy(createdBy);
        devSpecMapper.insert(spec);

        log.info("创建规范成功: specId={}, name={}, type={}", specId, name, specType);
        return spec;
    }

    @Override
    @Transactional
    public void updateSpec(String specId, String name, String specType, String content) {
        DevSpec spec = getSpecOrThrow(specId);

        String oldName = spec.getName();
        spec.setName(name);
        spec.setSpecType(specType);
        spec.setContent(content);
        devSpecMapper.updateById(spec);

        // 同步更新已关联项目的规范文件和 CLAUDE.md
        List<SpecProjectLink> links = specProjectLinkMapper.selectList(
                new LambdaQueryWrapper<SpecProjectLink>().eq(SpecProjectLink::getSpecId, specId));
        for (SpecProjectLink link : links) {
            try {
                Project project = getProjectOrThrow(link.getProjectId());
                String resolvedPath = resolveProjectPath(project);
                if (resolvedPath != null) {
                    Path codePath = Path.of(resolvedPath);
                    // 更新规范文件内容
                    writeSpecFile(codePath, specId, content);
                    // 如果名称或类型变了，需要更新 CLAUDE.md 中的引用
                    if (!oldName.equals(name)) {
                        removeFromClaudeMd(codePath, specId, oldName);
                        injectClaudeMd(codePath, specId, name, specType);
                    }
                }
            } catch (Exception e) {
                log.warn("同步规范文件到项目失败（忽略）: specId={}, projectId={}", specId, link.getProjectId(), e);
            }
        }

        log.info("更新规范成功: specId={}, 同步了 {} 个关联项目", specId, links.size());
    }

    @Override
    @Transactional
    public void deleteSpec(String specId) {
        DevSpec spec = getSpecOrThrow(specId);

        long linkCount = specProjectLinkMapper.selectCount(
                new LambdaQueryWrapper<SpecProjectLink>().eq(SpecProjectLink::getSpecId, specId));
        if (linkCount > 0) {
            throw new BusinessException("该规范已关联 " + linkCount + " 个项目，请先解除关联后再删除");
        }

        devSpecMapper.deleteById(spec);
        log.info("删除规范成功: specId={}", specId);
    }

    @Override
    public List<DevSpec> listSpecs() {
        return devSpecMapper.selectList(new LambdaQueryWrapper<DevSpec>()
                .orderByDesc(DevSpec::getCreatedAt));
    }

    @Override
    @Transactional
    public void linkToProject(String specId, String projectId) {
        DevSpec spec = getSpecOrThrow(specId);
        Project project = getProjectOrThrow(projectId);

        String resolvedPath = resolveProjectPath(project);
        if (resolvedPath == null) {
            throw new BusinessException("项目 codePath 为空，请确认项目已完成初始化（specId=" + specId + ", projectId=" + projectId + "）");
        }

        // 幂等：已存在关联则直接返回
        SpecProjectLink existing = specProjectLinkMapper.selectOne(
                new LambdaQueryWrapper<SpecProjectLink>()
                        .eq(SpecProjectLink::getSpecId, specId)
                        .eq(SpecProjectLink::getProjectId, projectId));
        if (existing != null) {
            log.info("规范已关联该项目，跳过（幂等）: specId={}, projectId={}", specId, projectId);
            return;
        }

        Path codePath = Path.of(resolvedPath);

        // 1. 先写规范文件（文件失败则异常，事务回滚）
        writeSpecFile(codePath, specId, spec.getContent());

        // 2. 注入 CLAUDE.md（幂等）
        injectClaudeMd(codePath, specId, spec.getName(), spec.getSpecType());

        // 3. 插入关联记录
        SpecProjectLink link = new SpecProjectLink();
        link.setSpecId(specId);
        link.setProjectId(projectId);
        specProjectLinkMapper.insert(link);

        log.info("规范关联项目成功: specId={}, projectId={}", specId, projectId);
    }

    @Override
    @Transactional
    public void unlinkFromProject(String specId, String projectId) {
        DevSpec spec = getSpecOrThrow(specId);
        Project project = getProjectOrThrow(projectId);

        SpecProjectLink link = specProjectLinkMapper.selectOne(
                new LambdaQueryWrapper<SpecProjectLink>()
                        .eq(SpecProjectLink::getSpecId, specId)
                        .eq(SpecProjectLink::getProjectId, projectId));
        if (link == null) {
            log.info("规范与项目未关联，静默返回: specId={}, projectId={}", specId, projectId);
            return;
        }

        String resolvedPath = resolveProjectPath(project);
        if (resolvedPath != null) {
            Path codePath = Path.of(resolvedPath);
            // 删除规范文件（不存在则静默忽略）
            deleteSpecFileIfExists(codePath, specId);
            // 从 CLAUDE.md 移除引用行（不存在则静默忽略）
            removeFromClaudeMd(codePath, specId, spec.getName());
        }

        // 物理删除关联记录
        specProjectLinkMapper.delete(
                new LambdaQueryWrapper<SpecProjectLink>()
                        .eq(SpecProjectLink::getSpecId, specId)
                        .eq(SpecProjectLink::getProjectId, projectId));

        log.info("规范解除关联成功: specId={}, projectId={}", specId, projectId);
    }

    @Override
    public List<SpecProjectLink> listLinkedProjects(String specId) {
        return specProjectLinkMapper.selectList(
                new LambdaQueryWrapper<SpecProjectLink>().eq(SpecProjectLink::getSpecId, specId));
    }

    // ============================= 私有方法 =============================

    /** 写规范文件：{codePath}/rules/{specId}.md */
    private void writeSpecFile(Path codePath, String specId, String content) {
        try {
            Path rulesDir = codePath.resolve("rules");
            Files.createDirectories(rulesDir);
            Files.writeString(rulesDir.resolve(specId + ".md"), content != null ? content : "");
        } catch (IOException e) {
            log.error("写入规范文件失败: codePath={}, specId={}", codePath, specId, e);
            throw new BusinessException("写入规范文件失败: " + e.getMessage());
        }
    }

    /** 区块标题标记，用于定位规范注入区块 */
    private static final String SECTION_HEADER = "## 开发规范（自动注入，请勿手动删除此区块）";
    private static final String SECTION_DESC = "在进行 UI 设计、前端开发、后端开发过程中，必须强制遵循以下规范文件中的内容：";

    /**
     * 在 {codePath}/CLAUDE.md 中注入规范引用（幂等）。
     * 若区块已存在则追加规则行；否则创建完整区块。
     */
    private void injectClaudeMd(Path codePath, String specId, String specName, String specType) {
        Path claudeMdPath = codePath.resolve("CLAUDE.md");
        try {
            String existing = Files.exists(claudeMdPath) ? Files.readString(claudeMdPath) : "";

            // 幂等：已有该规范引用则跳过
            if (existing.contains("rules/" + specId + ".md")) {
                log.info("CLAUDE.md 中已存在规范引用，跳过（幂等）: specId={}", specId);
                return;
            }

            String ruleLine = buildRuleLine(specId, specName, specType);

            String updated;
            if (existing.contains(SECTION_HEADER)) {
                // 区块已存在，在区块末尾追加规则行
                updated = appendRuleToExistingSection(existing, ruleLine);
            } else {
                // 区块不存在，创建完整区块
                updated = existing + "\n\n" + SECTION_HEADER + "\n\n" + SECTION_DESC + "\n" + ruleLine + "\n";
            }

            Files.writeString(claudeMdPath, updated, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        } catch (IOException e) {
            log.error("注入 CLAUDE.md 失败: codePath={}, specId={}", codePath, specId, e);
            throw new BusinessException("注入 CLAUDE.md 失败: " + e.getMessage());
        }
    }

    /** 在已有区块的最后一条 `- rules/` 行之后追加新规则行 */
    private String appendRuleToExistingSection(String content, String ruleLine) {
        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean inSection = false;
        boolean inserted = false;
        for (int i = 0; i < lines.length; i++) {
            sb.append(lines[i]);
            if (lines[i].contains(SECTION_HEADER)) {
                inSection = true;
            }
            if (inSection && !inserted) {
                // 找到区块内最后一个 rules/ 行的位置，在其后插入
                boolean isRuleLine = lines[i].trim().startsWith("- rules/");
                boolean nextIsNotRule = (i + 1 >= lines.length) || !lines[i + 1].trim().startsWith("- rules/");
                if (isRuleLine && nextIsNotRule) {
                    sb.append("\n").append(ruleLine);
                    inserted = true;
                }
            }
            if (i < lines.length - 1) {
                sb.append("\n");
            }
        }
        // 如果区块存在但没有规则行（被清空的残留区块），在说明行后追加
        if (!inserted) {
            String descLine = SECTION_DESC;
            int descIdx = sb.indexOf(descLine);
            if (descIdx >= 0) {
                int insertPos = descIdx + descLine.length();
                sb.insert(insertPos, "\n" + ruleLine);
            }
        }
        return sb.toString();
    }

    /** 构建单条规则引用行 */
    private String buildRuleLine(String specId, String specName, String specType) {
        String typeLabel = switch (specType) {
            case "UI" -> "UI 设计";
            case "FRONTEND" -> "前端开发";
            case "BACKEND" -> "后端开发";
            default -> specType;
        };
        return "- rules/" + specId + ".md：" + specName + "（类型：" + typeLabel + "）";
    }

    /**
     * 从 CLAUDE.md 中移除对应规范的引用行。
     * 如果移除后区块内没有剩余规则，则清理整个区块（标题 + 说明）。
     */
    private void removeFromClaudeMd(Path codePath, String specId, String specName) {
        Path claudeMdPath = codePath.resolve("CLAUDE.md");
        if (!Files.exists(claudeMdPath)) {
            return;
        }
        try {
            String content = Files.readString(claudeMdPath);
            String marker = "rules/" + specId + ".md";
            if (!content.contains(marker)) {
                return;
            }

            // 逐行过滤掉包含该规范引用的行
            String updated = content.lines()
                    .filter(line -> !line.contains(marker))
                    .collect(java.util.stream.Collectors.joining("\n"));

            // 检查区块内是否还有其他规则行
            boolean hasRemainingRules = updated.lines()
                    .anyMatch(line -> line.trim().startsWith("- rules/") && line.trim().contains(".md"));

            if (!hasRemainingRules) {
                // 没有剩余规则，清理整个区块（标题 + 说明 + 空行）
                updated = removeEntireSection(updated);
            }

            Files.writeString(claudeMdPath, updated, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            log.warn("从 CLAUDE.md 移除规范引用失败（忽略）: codePath={}, specId={}", codePath, specId, e);
        }
    }

    /** 移除整个规范注入区块（标题行 + 说明行 + 前后空行） */
    private String removeEntireSection(String content) {
        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean skipping = false;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(SECTION_HEADER)) {
                skipping = true;
                // 回溯删除前面的连续空行
                String built = sb.toString();
                while (built.endsWith("\n\n")) {
                    built = built.substring(0, built.length() - 1);
                }
                sb = new StringBuilder(built);
                continue;
            }
            if (skipping) {
                // 跳过区块内容：说明行和空行，直到遇到下一个非空且非区块内容的行
                String trimmed = lines[i].trim();
                if (trimmed.isEmpty() || trimmed.equals(SECTION_DESC) || trimmed.startsWith("- rules/")) {
                    continue;
                }
                // 遇到其他内容，停止跳过
                skipping = false;
            }
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    /** 物理删除规范文件（不存在则静默忽略） */
    private void deleteSpecFileIfExists(Path codePath, String specId) {
        try {
            Files.deleteIfExists(codePath.resolve("rules/" + specId + ".md"));
        } catch (IOException e) {
            log.warn("删除规范文件失败（忽略）: codePath={}, specId={}", codePath, specId, e);
        }
    }

    private DevSpec getSpecOrThrow(String specId) {
        DevSpec spec = devSpecMapper.selectOne(
                new LambdaQueryWrapper<DevSpec>().eq(DevSpec::getSpecId, specId));
        if (spec == null) {
            throw new BusinessException("规范不存在: " + specId);
        }
        return spec;
    }

    /** 优先取 codePath，fallback 到 localPath（兼容本地项目） */
    private String resolveProjectPath(Project project) {
        if (StringUtils.hasText(project.getCodePath())) {
            return project.getCodePath();
        }
        return StringUtils.hasText(project.getLocalPath()) ? project.getLocalPath() : null;
    }

    private Project getProjectOrThrow(String projectId) {
        Project project = projectMapper.selectOne(
                new LambdaQueryWrapper<Project>().eq(Project::getProjectId, projectId));
        if (project == null) {
            throw new BusinessException("项目不存在: " + projectId);
        }
        return project;
    }
}
