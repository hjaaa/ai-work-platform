package com.aiworkplatform.service.orchestrator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class StreamJsonParserTest {

    private StreamJsonParser parser;
    private StringBuilder fullOutput;
    private AtomicReference<String> sessionId;
    private List<String> pushedMessages;

    @BeforeEach
    void setUp() {
        pushedMessages = new ArrayList<>();
        parser = new StreamJsonParser(text -> pushedMessages.add(text));
        fullOutput = new StringBuilder();
        sessionId = new AtomicReference<>();
    }

    // --- result 事件解析 ---

    @Test
    void should_extractOutputAndSessionId_when_resultEvent() {
        String line = """
                {"type":"result","subtype":"success","result":"完整回复内容","session_id":"abc-123"}""";

        parser.parseLine(line, fullOutput, sessionId);

        assertEquals("完整回复内容", fullOutput.toString());
        assertEquals("abc-123", sessionId.get());
    }

    @Test
    void should_overwritePreviousOutput_when_resultEventArrives() {
        fullOutput.append("之前的部分内容");

        String line = """
                {"type":"result","subtype":"success","result":"最终完整回复","session_id":"s1"}""";

        parser.parseLine(line, fullOutput, sessionId);

        assertEquals("最终完整回复", fullOutput.toString());
    }

    @Test
    void should_keepSessionIdNull_when_resultHasNoSessionId() {
        String line = """
                {"type":"result","subtype":"success","result":"回复"}""";

        parser.parseLine(line, fullOutput, sessionId);

        assertEquals("回复", fullOutput.toString());
        assertNull(sessionId.get());
    }

    @Test
    void should_notOverwriteOutput_when_resultIsEmpty() {
        fullOutput.append("已有内容");

        String line = """
                {"type":"result","subtype":"error","result":"","session_id":"s1"}""";

        parser.parseLine(line, fullOutput, sessionId);

        assertEquals("已有内容", fullOutput.toString());
        assertEquals("s1", sessionId.get());
    }

    // --- assistant 事件解析 ---

    @Test
    void should_pushTextContent_when_assistantEvent() {
        String line = """
                {"type":"assistant","message":{"content":[{"type":"text","text":"你好世界"}]}}""";

        parser.parseLine(line, fullOutput, sessionId);

        assertEquals(1, pushedMessages.size());
        assertEquals("你好世界", pushedMessages.get(0));
    }

    @Test
    void should_pushMultipleTextBlocks_when_assistantHasMultipleContent() {
        String line = """
                {"type":"assistant","message":{"content":[{"type":"text","text":"第一段"},{"type":"text","text":"第二段"}]}}""";

        parser.parseLine(line, fullOutput, sessionId);

        assertEquals(2, pushedMessages.size());
        assertEquals("第一段", pushedMessages.get(0));
        assertEquals("第二段", pushedMessages.get(1));
    }

    @Test
    void should_skipNonTextContent_when_assistantHasToolUse() {
        String line = """
                {"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash"},{"type":"text","text":"执行完毕"}]}}""";

        parser.parseLine(line, fullOutput, sessionId);

        assertEquals(1, pushedMessages.size());
        assertEquals("执行完毕", pushedMessages.get(0));
    }

    @Test
    void should_notPush_when_assistantTextIsEmpty() {
        String line = """
                {"type":"assistant","message":{"content":[{"type":"text","text":""}]}}""";

        parser.parseLine(line, fullOutput, sessionId);

        assertTrue(pushedMessages.isEmpty());
    }

    // --- 容错 ---

    @Test
    void should_skipGracefully_when_lineIsNull() {
        parser.parseLine(null, fullOutput, sessionId);

        assertEquals("", fullOutput.toString());
        assertNull(sessionId.get());
        assertTrue(pushedMessages.isEmpty());
    }

    @Test
    void should_skipGracefully_when_lineIsBlank() {
        parser.parseLine("   ", fullOutput, sessionId);

        assertEquals("", fullOutput.toString());
        assertTrue(pushedMessages.isEmpty());
    }

    @Test
    void should_skipGracefully_when_lineIsNotJson() {
        parser.parseLine("这不是JSON", fullOutput, sessionId);

        assertEquals("", fullOutput.toString());
        assertTrue(pushedMessages.isEmpty());
    }

    @Test
    void should_ignoreUnknownEventType_when_systemEvent() {
        String line = """
                {"type":"system","subtype":"init","session_id":"xyz"}""";

        parser.parseLine(line, fullOutput, sessionId);

        assertEquals("", fullOutput.toString());
        assertNull(sessionId.get());
        assertTrue(pushedMessages.isEmpty());
    }

    @Test
    void should_ignoreUnknownEventType_when_rateLimitEvent() {
        String line = """
                {"type":"rate_limit_event","rate_limit_info":{"status":"allowed"}}""";

        parser.parseLine(line, fullOutput, sessionId);

        assertTrue(pushedMessages.isEmpty());
    }
}
