package com.aiworkplatform.service.chat;

import java.util.List;

/**
 * 将用户上传的文件路径拼入 prompt，让 CC CLI 通过 Read 工具读取文件
 */
public final class FilePromptBuilder {

    private FilePromptBuilder() {
    }

    /**
     * 构建带文件路径的 prompt。无文件时返回原始消息。
     *
     * @param message   用户输入的文本消息
     * @param filePaths 文件相对路径列表（相对于 workspaceDir，如 "file/xxx.txt"）
     * @return 拼接后的 prompt
     */
    public static String build(String message, List<String> filePaths) {
        if (filePaths == null || filePaths.isEmpty()) {
            return message;
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("用户上传了以下文件，请使用 Read 工具读取文件内容后再回答：\n");
        for (String path : filePaths) {
            prompt.append("- ").append(path).append("\n");
        }
        prompt.append("\n用户消息：").append(message);
        return prompt.toString();
    }
}
