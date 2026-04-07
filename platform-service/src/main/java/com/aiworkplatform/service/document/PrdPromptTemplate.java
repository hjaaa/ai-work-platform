package com.aiworkplatform.service.document;

/**
 * PRD 生成的 prompt 模板
 */
public final class PrdPromptTemplate {

    private PrdPromptTemplate() {
    }

    /**
     * 初始 PRD 生成 prompt（用户第一次输入需求描述时）
     */
    public static String buildInitialPrompt(String userDescription) {
        return """
                你是一个专业的产品经理助手，帮助用户将模糊的需求描述转化为结构化的 PRD 文档。

                ## 用户的需求描述
                %s

                ## 你的任务
                1. 分析用户的需求描述，识别出核心功能和关键细节
                2. 如果描述中有不清晰的地方，列出需要追问的问题（不超过 5 个最关键的）
                3. 基于已有信息，生成一份结构化的 PRD 初稿

                ## 输出格式（严格遵循）
                先输出追问问题（如有），格式：
                ```
                [QUESTIONS]
                1. 问题一？
                2. 问题二？
                [/QUESTIONS]
                ```

                然后输出 PRD 内容，格式：
                ```
                [PRD]
                # {产品/功能名称}

                ## 1. 背景与目标
                {描述产品背景、解决什么问题、核心目标}

                ## 2. 目标用户
                {描述目标用户群体和使用场景}

                ## 3. 用户故事
                ### US-001: {故事标题}（优先级: P1/P2/P3）
                - 描述: {作为xxx，我希望xxx，以便xxx}
                - 验收条件:
                  - Given xxx, When xxx, Then xxx

                ### US-002: {故事标题}（优先级: P1/P2/P3）
                ...

                ## 4. 功能需求
                - FR-001: {系统必须xxx}
                - FR-002: {系统必须xxx}
                ...

                ## 5. 非功能需求
                - {性能/安全/兼容性等要求}

                ## 6. 验收标准
                - {可量化的成功指标}

                ## 7. 假设与约束
                - {前提假设和已知限制}
                [/PRD]
                ```

                注意：
                - 功能边界限定在 CRUD 页面、表单配置、前端样式修改范围内
                - 用户故事按优先级排序
                - 需求描述不清晰的部分标记为 [待确认]
                """.formatted(userDescription);
    }

    /**
     * 追问回答后的细化 prompt
     */
    public static String buildRefinementPrompt(String currentPrd, String userAnswer) {
        return """
                你之前生成了一份 PRD 初稿，用户回答了你的追问。请根据用户的回答更新 PRD。

                ## 当前 PRD
                %s

                ## 用户的回答
                %s

                ## 你的任务
                1. 根据用户回答，更新 PRD 中标记为 [待确认] 的部分
                2. 如果还有不清晰的地方，继续追问（不超过 3 个）
                3. 输出更新后的完整 PRD（使用相同格式）

                使用与之前相同的 [QUESTIONS] 和 [PRD] 格式输出。
                """.formatted(currentPrd, userAnswer);
    }

    /**
     * PRD 最终确认 prompt（用户确认后，去掉所有 [待确认] 标记）
     */
    public static String buildFinalizePrompt(String currentPrd) {
        return """
                请检查以下 PRD 文档，去掉所有 [待确认] 标记，补全所有空缺内容，
                确保文档完整、专业、可直接用于开发。

                ## PRD
                %s

                使用 [PRD]...[/PRD] 格式输出最终版本。
                """.formatted(currentPrd);
    }
}
