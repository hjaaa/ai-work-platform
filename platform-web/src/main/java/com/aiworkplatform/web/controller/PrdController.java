package com.aiworkplatform.web.controller;

import com.aiworkplatform.common.result.Result;
import com.aiworkplatform.domain.entity.Project;
import com.aiworkplatform.service.document.PrdExportService;
import com.aiworkplatform.service.document.PrdGenerateService;
import com.aiworkplatform.service.project.ProjectService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/projects/{projectId}/prd")
public class PrdController {

    private final PrdGenerateService prdGenerateService;
    private final PrdExportService prdExportService;
    private final ProjectService projectService;

    public PrdController(PrdGenerateService prdGenerateService,
                         PrdExportService prdExportService,
                         ProjectService projectService) {
        this.prdGenerateService = prdGenerateService;
        this.prdExportService = prdExportService;
        this.projectService = projectService;
    }

    /**
     * 发起 PRD 生成（第一轮）
     */
    @PostMapping("/generate")
    public Result<Void> generatePrd(@PathVariable String projectId,
                                    @RequestParam @NotBlank String description) {
        prdGenerateService.generateInitialPrd(projectId, description);
        return Result.success();
    }

    /**
     * 细化 PRD（回答追问）
     */
    @PostMapping("/refine")
    public Result<Void> refinePrd(@PathVariable String projectId,
                                  @RequestParam @NotBlank String currentPrd,
                                  @RequestParam @NotBlank String answer) {
        prdGenerateService.refinePrd(projectId, currentPrd, answer);
        return Result.success();
    }

    /**
     * 最终确认 PRD
     */
    @PostMapping("/finalize")
    public Result<Void> finalizePrd(@PathVariable String projectId,
                                    @RequestParam @NotBlank String currentPrd) {
        prdGenerateService.finalizePrd(projectId, currentPrd);
        return Result.success();
    }

    /**
     * 导出 PRD 为 Word 文档
     */
    @PostMapping("/export")
    public ResponseEntity<byte[]> exportPrd(@PathVariable String projectId,
                                            @RequestParam @NotBlank String prdContent) {
        Project project = projectService.getByProjectId(projectId);
        byte[] docBytes = prdExportService.exportToWord(prdContent, project.getName());

        String fileName = URLEncoder.encode(project.getName() + "_PRD.docx", StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(docBytes);
    }
}
