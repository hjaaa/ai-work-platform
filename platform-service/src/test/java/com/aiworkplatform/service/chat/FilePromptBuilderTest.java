package com.aiworkplatform.service.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试文件路径拼入 prompt 的逻辑
 */
class FilePromptBuilderTest {

    @Test
    void should_build_prompt_with_single_file() {
        String prompt = FilePromptBuilder.build("帮我分析这个文件", List.of("file/report.docx"));

        assertTrue(prompt.contains("file/report.docx"));
        assertTrue(prompt.contains("帮我分析这个文件"));
    }

    @Test
    void should_build_prompt_with_multiple_files() {
        String prompt = FilePromptBuilder.build("对比这两个文件",
                List.of("file/a.txt", "file/b.txt"));

        assertTrue(prompt.contains("file/a.txt"));
        assertTrue(prompt.contains("file/b.txt"));
        assertTrue(prompt.contains("对比这两个文件"));
    }

    @Test
    void should_return_original_message_when_no_files() {
        String message = "普通消息";
        assertEquals(message, FilePromptBuilder.build(message, null));
        assertEquals(message, FilePromptBuilder.build(message, List.of()));
    }
}
