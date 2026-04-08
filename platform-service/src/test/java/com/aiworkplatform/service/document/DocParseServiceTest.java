package com.aiworkplatform.service.document;

import com.aiworkplatform.common.exception.BusinessException;
import com.aiworkplatform.service.document.impl.DocParseServiceImpl;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class DocParseServiceTest {

    private DocParseServiceImpl docParseService;

    @BeforeEach
    void setUp() {
        docParseService = new DocParseServiceImpl();
    }

    @Test
    void should_extractText_when_validDocxProvided() throws IOException {
        // 创建一个简单的 docx 文件
        byte[] docBytes = createSimpleDocx("这是一个测试段落", "第二个段落");

        DocParseResult result = docParseService.parseWord(
                new ByteArrayInputStream(docBytes), "test.docx");

        assertNotNull(result);
        assertTrue(result.getRawText().contains("测试段落"));
        assertTrue(result.getRawText().contains("第二个段落"));
    }

    @Test
    void should_throwException_when_invalidDocxProvided() {
        byte[] invalidBytes = "not a docx file".getBytes();

        assertThrows(BusinessException.class, () ->
                docParseService.parseWord(new ByteArrayInputStream(invalidBytes), "invalid.docx"));
    }

    @Test
    void should_throwException_when_urlUnreachable() {
        assertThrows(BusinessException.class, () ->
                docParseService.fetchOnlineDoc("http://nonexistent.invalid/doc"));
    }

    private byte[] createSimpleDocx(String... paragraphs) throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            for (String text : paragraphs) {
                XWPFParagraph para = document.createParagraph();
                para.createRun().setText(text);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            return out.toByteArray();
        }
    }
}
