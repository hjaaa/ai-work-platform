package com.aiworkplatform.service.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class SkillPromptConverterTest {

    // --- convert() 正常路径 ---

    @Test
    void should_convert_skill_command_with_args() {
        String result = SkillPromptConverter.convert("$req-dev 实现用户登录功能");

        assertTrue(result.contains("req-dev"));
        assertTrue(result.contains("实现用户登录功能"));
        assertFalse(result.startsWith("$"));
    }

    @Test
    void should_convert_skill_command_without_args() {
        String result = SkillPromptConverter.convert("$req-dev");

        assertTrue(result.contains("req-dev"));
        assertFalse(result.startsWith("$"));
    }

    @Test
    void should_convert_namespaced_skill() {
        // 支持 superpowers:brainstorming 这类带命名空间的 skill
        String result = SkillPromptConverter.convert("$superpowers:brainstorming 设计方案");

        assertTrue(result.contains("superpowers:brainstorming"));
        assertTrue(result.contains("设计方案"));
    }

    @Test
    void should_convert_skill_with_underscore_in_name() {
        String result = SkillPromptConverter.convert("$my_skill 参数");

        assertTrue(result.contains("my_skill"));
        assertTrue(result.contains("参数"));
    }

    // --- convert() 透传（不转换） ---

    @Test
    void should_passthrough_normal_message() {
        String input = "请帮我实现用户登录功能";
        assertEquals(input, SkillPromptConverter.convert(input));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void should_passthrough_null_and_empty(String input) {
        assertEquals(input, SkillPromptConverter.convert(input));
    }

    @Test
    void should_passthrough_blank_string() {
        assertEquals("   ", SkillPromptConverter.convert("   "));
    }

    @Test
    void should_passthrough_dollar_sign_in_middle() {
        // $ 不在开头，不视为 skill 命令
        String input = "价格是 $100";
        assertEquals(input, SkillPromptConverter.convert(input));
    }

    @Test
    void should_passthrough_dollar_followed_by_number() {
        // $123 不是合法 skill 名（skill 名必须以字母开头）
        String input = "$123";
        assertEquals(input, SkillPromptConverter.convert(input));
    }

    @Test
    void should_passthrough_lone_dollar_sign() {
        String input = "$";
        assertEquals(input, SkillPromptConverter.convert(input));
    }

    // --- isSkillCommand() ---

    @Test
    void should_detect_skill_command() {
        assertTrue(SkillPromptConverter.isSkillCommand("$req-dev 实现登录"));
        assertTrue(SkillPromptConverter.isSkillCommand("$optimize-flow"));
        assertTrue(SkillPromptConverter.isSkillCommand("$superpowers:brainstorming"));
    }

    @Test
    void should_not_detect_normal_message() {
        assertFalse(SkillPromptConverter.isSkillCommand("普通消息"));
        assertFalse(SkillPromptConverter.isSkillCommand(null));
        assertFalse(SkillPromptConverter.isSkillCommand(""));
        assertFalse(SkillPromptConverter.isSkillCommand("$123"));
        assertFalse(SkillPromptConverter.isSkillCommand("价格 $100"));
    }

    // --- 边界：前后空格处理 ---

    @Test
    void should_handle_leading_whitespace() {
        String result = SkillPromptConverter.convert("  $req-dev 参数");
        assertTrue(result.contains("req-dev"));
        assertFalse(result.startsWith("$"));
    }
}
