package com.aiworkplatform.service.document;

import com.aiworkplatform.service.document.impl.PrdExportServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PrdExportServiceTest {

    private PrdExportServiceImpl prdExportService;

    @BeforeEach
    void setUp() {
        prdExportService = new PrdExportServiceImpl();
    }

    @Test
    void should_generateValidDocx_when_prdContentProvided() {
        String prdContent = """
                # 员工管理系统

                ## 1. 背景与目标
                管理公司员工的基本信息，支持增删改查。

                ## 2. 功能需求
                - FR-001: 系统必须支持员工信息的录入
                - FR-002: 系统必须支持按姓名搜索员工

                ## 3. 验收标准
                1. 能成功创建员工记录
                2. 能按姓名搜索到员工
                """;

        byte[] result = prdExportService.exportToWord(prdContent, "员工管理系统");

        assertNotNull(result);
        // docx 文件以 PK 开头（ZIP 格式）
        assertTrue(result.length > 100);
        assertEquals((byte) 'P', result[0]);
        assertEquals((byte) 'K', result[1]);
    }

    @Test
    void should_handleEmptyContent_when_prdContentEmpty() {
        byte[] result = prdExportService.exportToWord("", "空项目");

        assertNotNull(result);
        assertTrue(result.length > 0);
    }
}
