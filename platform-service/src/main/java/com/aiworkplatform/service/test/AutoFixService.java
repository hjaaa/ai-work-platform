package com.aiworkplatform.service.test;

import com.aiworkplatform.service.chat.MessagePushService;
import com.aiworkplatform.service.orchestrator.ClaudeCodeOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/**
 * 测试失败自动修复服务
 * 测试失败 → 将失败信息反馈给 Claude Code → 重新测试 → 最多 3 轮
 */
@Service
public class AutoFixService {

    private static final Logger log = LoggerFactory.getLogger(AutoFixService.class);
    private static final int MAX_FIX_ROUNDS = 3;

    private final TestRunnerService testRunnerService;
    private final ClaudeCodeOrchestrator orchestrator;
    private final MessagePushService messagePushService;

    public AutoFixService(TestRunnerService testRunnerService,
                          ClaudeCodeOrchestrator orchestrator,
                          MessagePushService messagePushService) {
        this.testRunnerService = testRunnerService;
        this.orchestrator = orchestrator;
        this.messagePushService = messagePushService;
    }

    /**
     * 执行测试并在失败时自动修复
     *
     * @return 最终测试结果
     */
    public TestResult runWithAutoFix(String projectId, Path projectDir) {
        for (int round = 1; round <= MAX_FIX_ROUNDS; round++) {
            TestResult result = testRunnerService.runAllTests(projectId, projectDir);

            if (result.isSuccess()) {
                if (round > 1) {
                    messagePushService.pushProgress(projectId,
                            "第 " + round + " 轮修复后测试全部通过");
                }
                return result;
            }

            // 最后一轮失败，不再修复
            if (round == MAX_FIX_ROUNDS) {
                log.warn("自动修复已达上限: projectId={}, round={}, failedTests={}",
                        projectId, round, result.getFailedTests());
                messagePushService.pushProgress(projectId,
                        "已尝试 " + MAX_FIX_ROUNDS + " 轮自动修复，仍有 "
                                + result.getFailedTests() + " 个测试失败，需要开发人员介入");
                return result;
            }

            // 构建修复 prompt 并调用 Claude Code
            messagePushService.pushProgress(projectId,
                    "第 " + round + " 轮测试有 " + result.getFailedTests() + " 个失败，正在尝试自动修复...");

            String fixPrompt = buildFixPrompt(result);
            try {
                orchestrator.execute(projectId, projectDir, fixPrompt);
                log.info("第 {} 轮自动修复完成: projectId={}", round, projectId);
            } catch (Exception e) {
                log.error("自动修复执行失败: projectId={}, round={}", projectId, round, e);
                messagePushService.pushProgress(projectId,
                        "自动修复执行异常: " + e.getMessage());
                return result;
            }
        }

        // 不应到达此处，for 循环内必定 return
        throw new IllegalStateException("unreachable");
    }

    private String buildFixPrompt(TestResult failedResult) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("测试执行失败，请修复以下问题：\n\n");

        List<String> details = failedResult.getFailureDetails();
        if (details != null && !details.isEmpty()) {
            prompt.append("## 失败详情\n");
            for (String detail : details) {
                prompt.append("- ").append(detail).append("\n");
            }
        }

        prompt.append("\n## 要求\n");
        prompt.append("- 只修复失败的测试相关代码，不要改动其他功能\n");
        prompt.append("- 如果是测试本身写错了，修正测试；如果是实现有 bug，修正实现\n");
        prompt.append("- 确保修复后所有测试都能通过\n");

        return prompt.toString();
    }
}
