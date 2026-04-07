package com.aiworkplatform.web.controller;

import com.aiworkplatform.common.result.Result;
import com.aiworkplatform.service.document.DocParseResult;
import com.aiworkplatform.service.document.DocParseService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/projects/{projectId}/doc")
public class DocController {

    private final DocParseService docParseService;

    public DocController(DocParseService docParseService) {
        this.docParseService = docParseService;
    }

    /**
     * 上传并解析 Word 文档
     */
    @PostMapping("/upload")
    public Result<DocParseResult> uploadDoc(@PathVariable String projectId,
                                            @RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            return Result.fail(400, "请选择文件");
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.endsWith(".docx")) {
            return Result.fail(400, "仅支持 .docx 格式");
        }
        DocParseResult result = docParseService.parseWord(file.getInputStream(), fileName);
        return Result.success(result);
    }

    /**
     * 解析在线文档链接
     */
    @PostMapping("/fetch")
    public Result<DocParseResult> fetchDoc(@PathVariable String projectId,
                                           @RequestParam @NotBlank String url) {
        DocParseResult result = docParseService.fetchOnlineDoc(url);
        return Result.success(result);
    }
}
