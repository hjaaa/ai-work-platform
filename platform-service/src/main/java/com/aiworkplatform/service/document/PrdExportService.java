package com.aiworkplatform.service.document;

/**
 * PRD Word 导出服务接口
 */
public interface PrdExportService {

    /**
     * 将 PRD Markdown 内容导出为 Word 文档字节数组
     */
    byte[] exportToWord(String prdContent, String projectName);
}
