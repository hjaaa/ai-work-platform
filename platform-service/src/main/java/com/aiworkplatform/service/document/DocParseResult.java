package com.aiworkplatform.service.document;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 文档解析结果
 */
@Data
@Builder
public class DocParseResult {

    private String rawText;
    private List<String> headings;
    private List<String> functionPoints;
}
