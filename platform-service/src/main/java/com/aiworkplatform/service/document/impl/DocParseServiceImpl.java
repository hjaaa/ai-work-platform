package com.aiworkplatform.service.document.impl;

import com.aiworkplatform.common.exception.BusinessException;
import com.aiworkplatform.service.document.DocParseResult;
import com.aiworkplatform.service.document.DocParseService;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Service
public class DocParseServiceImpl implements DocParseService {

    private static final Logger log = LoggerFactory.getLogger(DocParseServiceImpl.class);
    private static final int FETCH_TIMEOUT_MS = 15000;

    @Override
    public DocParseResult parseWord(InputStream inputStream, String fileName) {
        try (XWPFDocument document = new XWPFDocument(inputStream)) {
            StringBuilder textBuilder = new StringBuilder();
            List<String> headings = new ArrayList<>();

            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = paragraph.getText().trim();
                if (text.isEmpty()) {
                    continue;
                }

                String style = paragraph.getStyle();
                if (style != null && style.startsWith("Heading")) {
                    headings.add(text);
                }
                textBuilder.append(text).append("\n");
            }

            for (XWPFTable table : document.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    String rowText = row.getTableCells().stream()
                            .map(cell -> cell.getText().trim())
                            .reduce((a, b) -> a + " | " + b)
                            .orElse("");
                    if (!rowText.isEmpty()) {
                        textBuilder.append(rowText).append("\n");
                    }
                }
            }

            String rawText = textBuilder.toString();
            log.info("Word 文档解析完成: fileName={}, textLength={}, headings={}",
                    fileName, rawText.length(), headings.size());

            return DocParseResult.builder()
                    .rawText(rawText)
                    .headings(headings)
                    .functionPoints(List.of())
                    .build();

        } catch (Exception e) {
            log.error("解析 Word 文档失败: fileName={}", fileName, e);
            throw new BusinessException("文档解析失败: " + e.getMessage());
        }
    }

    @Override
    public DocParseResult fetchOnlineDoc(String url) {
        try {
            Document doc = Jsoup.connect(url)
                    .timeout(FETCH_TIMEOUT_MS)
                    .userAgent("Mozilla/5.0 (AI Work Platform)")
                    .get();

            String bodyText = extractMainContent(doc);

            List<String> headings = new ArrayList<>();
            Elements headingElements = doc.select("h1, h2, h3, h4");
            for (Element h : headingElements) {
                String text = h.text().trim();
                if (!text.isEmpty()) {
                    headings.add(text);
                }
            }

            log.info("在线文档抓取完成: url={}, textLength={}, headings={}",
                    url, bodyText.length(), headings.size());

            return DocParseResult.builder()
                    .rawText(bodyText)
                    .headings(headings)
                    .functionPoints(List.of())
                    .build();

        } catch (IOException e) {
            log.error("抓取在线文档失败: url={}", url, e);
            throw new BusinessException("文档抓取失败: " + e.getMessage());
        }
    }

    private String extractMainContent(Document doc) {
        doc.select("nav, header, footer, script, style, .sidebar, .navigation, .menu").remove();

        Elements mainContent = doc.select("article, main, .content, .post-body, .article-body");
        if (!mainContent.isEmpty()) {
            return mainContent.first().text();
        }

        Element body = doc.body();
        return body != null ? body.text() : doc.text();
    }
}
