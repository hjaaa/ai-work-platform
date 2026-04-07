package com.aiworkplatform.service.document;

import com.aiworkplatform.common.exception.BusinessException;
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

/**
 * 文档解析服务
 * 支持 Word (.docx) 文件解析和在线文档链接抓取
 */
@Service
public class DocParseService {

    private static final Logger log = LoggerFactory.getLogger(DocParseService.class);
    private static final int FETCH_TIMEOUT_MS = 15000;

    /**
     * 解析 Word 文档
     */
    public DocParseResult parseWord(InputStream inputStream, String fileName) {
        try (XWPFDocument document = new XWPFDocument(inputStream)) {
            StringBuilder textBuilder = new StringBuilder();
            List<String> headings = new ArrayList<>();

            // 提取段落
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

            // 提取表格
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

    /**
     * 抓取在线文档链接内容
     */
    public DocParseResult fetchOnlineDoc(String url) {
        try {
            Document doc = Jsoup.connect(url)
                    .timeout(FETCH_TIMEOUT_MS)
                    .userAgent("Mozilla/5.0 (AI Work Platform)")
                    .get();

            // 提取正文
            String bodyText = extractMainContent(doc);

            // 提取标题
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

    /**
     * 提取页面主体内容，过滤导航栏、页脚等干扰元素
     */
    private String extractMainContent(Document doc) {
        // 移除常见的非正文元素
        doc.select("nav, header, footer, script, style, .sidebar, .navigation, .menu").remove();

        // 优先取 article / main 标签
        Elements mainContent = doc.select("article, main, .content, .post-body, .article-body");
        if (!mainContent.isEmpty()) {
            return mainContent.first().text();
        }

        // 降级到 body
        Element body = doc.body();
        return body != null ? body.text() : doc.text();
    }
}
