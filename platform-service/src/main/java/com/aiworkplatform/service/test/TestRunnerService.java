package com.aiworkplatform.service.test;

import com.aiworkplatform.common.exception.BusinessException;
import com.aiworkplatform.service.chat.MessagePushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 测试执行服务
 * 在生成项目的工作区中执行 mvn test / npm test，解析结果
 */
@Service
public class TestRunnerService {

    private static final Logger log = LoggerFactory.getLogger(TestRunnerService.class);
    private static final int TEST_TIMEOUT_MINUTES = 5;

    // 匹配 Maven Surefire 报告摘要: Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
    private static final Pattern SUREFIRE_SUMMARY = Pattern.compile(
            "Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+),\\s*Skipped:\\s*(\\d+)");

    private final MessagePushService messagePushService;

    public TestRunnerService(MessagePushService messagePushService) {
        this.messagePushService = messagePushService;
    }

    /**
     * 执行后端测试（mvn test）
     */
    public TestResult runBackendTests(String projectId, Path projectDir) {
        Path backendDir = projectDir.resolve("backend");
        if (!Files.exists(backendDir.resolve("pom.xml"))) {
            log.warn("未找到后端 pom.xml: {}", backendDir);
            return TestResult.builder().success(true).rawOutput("跳过：未检测到后端项目").build();
        }

        messagePushService.pushProgress(projectId, "正在执行后端测试（mvn test）...");
        return executeCommand(projectId, backendDir, new String[]{"mvn", "test", "-q"}, this::parseMavenOutput);
    }

    /**
     * 执行前端测试（npm test）
     */
    public TestResult runFrontendTests(String projectId, Path projectDir) {
        Path frontendDir = projectDir.resolve("frontend");
        if (!Files.exists(frontendDir.resolve("package.json"))) {
            log.warn("未找到前端 package.json: {}", frontendDir);
            return TestResult.builder().success(true).rawOutput("跳过：未检测到前端项目").build();
        }

        messagePushService.pushProgress(projectId, "正在执行前端测试（npm test）...");
        return executeCommand(projectId, frontendDir, new String[]{"npm", "test", "--", "--run"}, this::parseNpmOutput);
    }

    /**
     * 执行全部测试（后端 + 前端）
     */
    public TestResult runAllTests(String projectId, Path projectDir) {
        TestResult backendResult = runBackendTests(projectId, projectDir);
        TestResult frontendResult = runFrontendTests(projectId, projectDir);

        // 合并结果
        List<String> allFailures = new ArrayList<>();
        if (backendResult.getFailureDetails() != null) {
            allFailures.addAll(backendResult.getFailureDetails());
        }
        if (frontendResult.getFailureDetails() != null) {
            allFailures.addAll(frontendResult.getFailureDetails());
        }

        boolean allSuccess = backendResult.isSuccess() && frontendResult.isSuccess();
        int totalTests = safeInt(backendResult.getTotalTests()) + safeInt(frontendResult.getTotalTests());
        int passedTests = safeInt(backendResult.getPassedTests()) + safeInt(frontendResult.getPassedTests());
        int failedTests = safeInt(backendResult.getFailedTests()) + safeInt(frontendResult.getFailedTests());

        TestResult merged = TestResult.builder()
                .success(allSuccess)
                .totalTests(totalTests)
                .passedTests(passedTests)
                .failedTests(failedTests)
                .failureDetails(allFailures)
                .rawOutput(backendResult.getRawOutput() + "\n---\n" + frontendResult.getRawOutput())
                .build();

        // 推送汇总
        String summary = String.format("测试完成: 总计 %d, 通过 %d, 失败 %d", totalTests, passedTests, failedTests);
        messagePushService.pushProgress(projectId, allSuccess ? summary : summary + "（有失败用例）");

        return merged;
    }

    private TestResult executeCommand(String projectId, Path workDir, String[] command,
                                      OutputParser parser) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        long startTime = System.currentTimeMillis();
        Process process = null;

        try {
            process = pb.start();
            StringBuilder output = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(TEST_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new BusinessException("测试执行超时（超过 " + TEST_TIMEOUT_MINUTES + " 分钟）");
            }

            int durationMs = (int) (System.currentTimeMillis() - startTime);
            return parser.parse(output.toString(), process.exitValue(), durationMs);

        } catch (IOException e) {
            log.error("测试执行失败: projectId={}, workDir={}", projectId, workDir, e);
            return TestResult.builder()
                    .success(false)
                    .failureDetails(List.of("测试启动失败: " + e.getMessage()))
                    .rawOutput(e.getMessage())
                    .build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return TestResult.builder()
                    .success(false)
                    .failureDetails(List.of("测试被中断"))
                    .rawOutput("Interrupted")
                    .build();
        }
    }

    /**
     * 解析 Maven 测试输出
     */
    private TestResult parseMavenOutput(String output, int exitCode, int durationMs) {
        int totalTests = 0, failures = 0, errors = 0, skipped = 0;
        List<String> failureDetails = new ArrayList<>();

        // 从 Surefire 摘要行提取数据（可能有多个测试模块，取累加值）
        Matcher matcher = SUREFIRE_SUMMARY.matcher(output);
        while (matcher.find()) {
            totalTests += Integer.parseInt(matcher.group(1));
            failures += Integer.parseInt(matcher.group(2));
            errors += Integer.parseInt(matcher.group(3));
            skipped += Integer.parseInt(matcher.group(4));
        }

        int failedTotal = failures + errors;

        // 提取失败测试的详情
        if (failedTotal > 0) {
            String[] lines = output.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.contains("<<< FAILURE!") || line.contains("<<< ERROR!")) {
                    failureDetails.add(line);
                    // 追加后续几行作为上下文
                    for (int j = i + 1; j < Math.min(i + 4, lines.length); j++) {
                        if (!lines[j].trim().isEmpty()) {
                            failureDetails.add("  " + lines[j].trim());
                        }
                    }
                }
            }
        }

        return TestResult.builder()
                .success(exitCode == 0 && failedTotal == 0)
                .totalTests(totalTests)
                .passedTests(totalTests - failedTotal - skipped)
                .failedTests(failedTotal)
                .skippedTests(skipped)
                .durationMs(durationMs)
                .failureDetails(failureDetails)
                .rawOutput(output)
                .build();
    }

    /**
     * 解析 npm test 输出（简单处理，根据退出码判断）
     */
    private TestResult parseNpmOutput(String output, int exitCode, int durationMs) {
        // npm test 的输出格式取决于测试框架，这里做通用解析
        List<String> failureDetails = new ArrayList<>();
        if (exitCode != 0) {
            // 提取包含 FAIL 或 Error 的行
            for (String line : output.split("\n")) {
                if (line.contains("FAIL") || line.contains("Error") || line.contains("✕") || line.contains("×")) {
                    failureDetails.add(line.trim());
                }
            }
        }

        return TestResult.builder()
                .success(exitCode == 0)
                .failedTests(exitCode == 0 ? 0 : failureDetails.size())
                .durationMs(durationMs)
                .failureDetails(failureDetails)
                .rawOutput(output)
                .build();
    }

    private int safeInt(int value) {
        return Math.max(value, 0);
    }

    @FunctionalInterface
    private interface OutputParser {
        TestResult parse(String output, int exitCode, int durationMs);
    }
}
