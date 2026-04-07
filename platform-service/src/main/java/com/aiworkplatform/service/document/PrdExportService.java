package com.aiworkplatform.service.document;

import com.aiworkplatform.common.exception.BusinessException;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.time.LocalDate;

/**
 * PRD Word 导出服务
 * 将 Markdown 格式的 PRD 内容渲染为 .docx 文件
 */
@Service
public class PrdExportService {

    private static final Logger log = LoggerFactory.getLogger(PrdExportService.class);

    /**
     * 将 PRD Markdown 内容导出为 Word 文档字节数组
     */
    public byte[] exportToWord(String prdContent, String projectName) {
        try (XWPFDocument document = new XWPFDocument()) {
            setPageSize(document);

            // 封面标题
            addTitle(document, projectName + " — PRD");
            addSubtitle(document, "生成日期: " + LocalDate.now());
            addEmptyLine(document);

            // 逐行解析 Markdown 并写入 Word
            String[] lines = prdContent.split("\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    addEmptyLine(document);
                } else if (trimmed.startsWith("# ")) {
                    addHeading(document, trimmed.substring(2).trim(), 1);
                } else if (trimmed.startsWith("## ")) {
                    addHeading(document, trimmed.substring(3).trim(), 2);
                } else if (trimmed.startsWith("### ")) {
                    addHeading(document, trimmed.substring(4).trim(), 3);
                } else if (trimmed.startsWith("- ")) {
                    addBulletItem(document, trimmed.substring(2).trim());
                } else if (trimmed.matches("^\\d+\\.\\s.*")) {
                    addNumberedItem(document, trimmed);
                } else {
                    addParagraph(document, trimmed);
                }
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            document.write(outputStream);
            return outputStream.toByteArray();

        } catch (IOException e) {
            log.error("PRD Word 导出失败: projectName={}", projectName, e);
            throw new BusinessException("PRD 导出失败: " + e.getMessage());
        }
    }

    private void setPageSize(XWPFDocument document) {
        CTBody body = document.getDocument().getBody();
        CTSectPr sectPr = body.isSetSectPr() ? body.getSectPr() : body.addNewSectPr();
        CTPageSz pageSize = sectPr.isSetPgSz() ? sectPr.getPgSz() : sectPr.addNewPgSz();
        // A4 纸张
        pageSize.setW(BigInteger.valueOf(11906));
        pageSize.setH(BigInteger.valueOf(16838));
    }

    private void addTitle(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        paragraph.setSpacingAfter(200);
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(22);
        run.setFontFamily("Microsoft YaHei");
    }

    private void addSubtitle(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setFontSize(12);
        run.setColor("666666");
        run.setFontFamily("Microsoft YaHei");
    }

    private void addHeading(XWPFDocument document, String text, int level) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingBefore(300);
        paragraph.setSpacingAfter(150);
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontFamily("Microsoft YaHei");

        switch (level) {
            case 1 -> run.setFontSize(18);
            case 2 -> run.setFontSize(15);
            case 3 -> run.setFontSize(13);
            default -> run.setFontSize(12);
        }
    }

    private void addParagraph(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingAfter(80);
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setFontSize(11);
        run.setFontFamily("Microsoft YaHei");
    }

    private void addBulletItem(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setIndentationLeft(400);
        paragraph.setSpacingAfter(40);
        XWPFRun run = paragraph.createRun();
        run.setText("• " + text);
        run.setFontSize(11);
        run.setFontFamily("Microsoft YaHei");
    }

    private void addNumberedItem(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setIndentationLeft(400);
        paragraph.setSpacingAfter(40);
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setFontSize(11);
        run.setFontFamily("Microsoft YaHei");
    }

    private void addEmptyLine(XWPFDocument document) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingAfter(60);
    }
}
