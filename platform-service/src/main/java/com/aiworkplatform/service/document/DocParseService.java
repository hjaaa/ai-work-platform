package com.aiworkplatform.service.document;

import java.io.InputStream;

/**
 * 文档解析服务接口
 */
public interface DocParseService {

    /**
     * 解析 Word 文档
     */
    DocParseResult parseWord(InputStream inputStream, String fileName);

    /**
     * 抓取在线文档链接内容
     */
    DocParseResult fetchOnlineDoc(String url);
}
