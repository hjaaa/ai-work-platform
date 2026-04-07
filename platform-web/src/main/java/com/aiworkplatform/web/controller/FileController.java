package com.aiworkplatform.web.controller;

import com.aiworkplatform.common.result.Result;
import com.aiworkplatform.domain.entity.Project;
import com.aiworkplatform.service.project.FileNode;
import com.aiworkplatform.service.project.FileService;
import com.aiworkplatform.service.project.ProjectService;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}/files")
public class FileController {

    private final FileService fileService;
    private final ProjectService projectService;

    public FileController(FileService fileService, ProjectService projectService) {
        this.fileService = fileService;
        this.projectService = projectService;
    }

    @GetMapping("/tree")
    public Result<List<FileNode>> getFileTree(@PathVariable String projectId) {
        Project project = projectService.getByProjectId(projectId);
        return Result.success(fileService.getFileTree(Path.of(project.getWorkspacePath())));
    }

    @GetMapping("/content")
    public Result<String> getFileContent(@PathVariable String projectId,
                                         @RequestParam String path) {
        Project project = projectService.getByProjectId(projectId);
        return Result.success(fileService.readFileContent(Path.of(project.getWorkspacePath()), path));
    }
}
