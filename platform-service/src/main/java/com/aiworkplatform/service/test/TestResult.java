package com.aiworkplatform.service.test;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 测试执行结果
 */
@Data
@Builder
public class TestResult {

    private boolean success;
    private int totalTests;
    private int passedTests;
    private int failedTests;
    private int skippedTests;
    private int durationMs;
    private List<String> failureDetails;
    private String rawOutput;
}
