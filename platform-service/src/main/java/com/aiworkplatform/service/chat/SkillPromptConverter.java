package com.aiworkplatform.service.chat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将工作台对话框中的 $skill-name 格式命令转换为自然语言 prompt，
 * 使 CC CLI 在 -p 模式下能通过 Skill 工具调用对应 skill。
 *
 * 示例：
 *   "$req-dev 实现用户登录功能" → "请使用 Skill 工具调用 req-dev skill 来处理以下内容：实现用户登录功能"
 *   "普通消息" → "普通消息"（原样返回）
 */
public final class SkillPromptConverter {

    /**
     * 匹配 $skill-name 格式：$ + skill名称（字母、数字、中划线、冒号）+ 可选参数
     * 冒号支持 superpowers:brainstorming 这类带命名空间的 skill
     */
    private static final Pattern SKILL_PATTERN = Pattern.compile("^\\$([a-zA-Z][a-zA-Z0-9:_-]*)\\s*(.*)$", Pattern.DOTALL);

    private SkillPromptConverter() {
    }

    /**
     * 检测并转换 skill 命令。非 skill 消息原样返回。
     */
    public static String convert(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }

        String trimmed = content.trim();
        Matcher matcher = SKILL_PATTERN.matcher(trimmed);
        if (!matcher.matches()) {
            return content;
        }

        String skillName = matcher.group(1);
        String args = matcher.group(2).trim();

        StringBuilder prompt = new StringBuilder();
        prompt.append("请使用 Skill 工具调用 ").append(skillName).append(" skill");

        if (!args.isEmpty()) {
            prompt.append("，参数为：").append(args);
        }

        prompt.append("。严格按照 skill 定义的流程执行，不要跳过任何步骤。");
        return prompt.toString();
    }

    /**
     * 判断是否为 skill 命令
     */
    public static boolean isSkillCommand(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        return SKILL_PATTERN.matcher(content.trim()).matches();
    }
}
