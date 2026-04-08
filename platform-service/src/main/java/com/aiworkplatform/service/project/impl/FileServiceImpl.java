package com.aiworkplatform.service.project.impl;

import com.aiworkplatform.common.exception.BusinessException;
import com.aiworkplatform.service.project.FileNode;
import com.aiworkplatform.service.project.FileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@Service
public class FileServiceImpl implements FileService {

    private static final Logger log = LoggerFactory.getLogger(FileServiceImpl.class);

    private static final Set<String> IGNORED = Set.of(
            ".git", "node_modules", "target", ".idea", ".vscode",
            ".DS_Store", "Thumbs.db"
    );

    private static final long MAX_FILE_SIZE = 1024 * 1024;
    private static final int MAX_DEPTH = 6;
    private static final int MAX_NODES = 500;
    private int nodeCount;

    @Override
    public List<FileNode> getFileTree(Path projectDir) {
        if (!Files.exists(projectDir)) {
            return List.of();
        }
        nodeCount = 0;
        return buildTree(projectDir, projectDir, 0);
    }

    @Override
    public String readFileContent(Path projectDir, String relativePath) {
        Path filePath = projectDir.resolve(relativePath).normalize();

        if (!filePath.startsWith(projectDir)) {
            throw new BusinessException(403, "不允许访问项目目录外的文件");
        }

        if (!Files.exists(filePath)) {
            throw new BusinessException(404, "文件不存在: " + relativePath);
        }

        if (Files.isDirectory(filePath)) {
            throw new BusinessException(400, "不能读取目录内容");
        }

        try {
            long size = Files.size(filePath);
            if (size > MAX_FILE_SIZE) {
                return "// 文件过大（" + size / 1024 + " KB），无法预览";
            }
            return Files.readString(filePath);
        } catch (IOException e) {
            log.error("读取文件失败: {}", filePath, e);
            throw new BusinessException("读取文件失败: " + e.getMessage());
        }
    }

    private List<FileNode> buildTree(Path rootDir, Path currentDir, int depth) {
        List<FileNode> nodes = new ArrayList<>();
        if (depth >= MAX_DEPTH || nodeCount >= MAX_NODES) {
            return nodes;
        }

        try (Stream<Path> entries = Files.list(currentDir)) {
            entries
                    .filter(p -> !IGNORED.contains(p.getFileName().toString()))
                    .sorted(Comparator.comparing((Path p) -> !Files.isDirectory(p))
                            .thenComparing(p -> p.getFileName().toString()))
                    .forEach(path -> {
                        if (nodeCount >= MAX_NODES) return;
                        nodeCount++;
                        String relativePath = rootDir.relativize(path).toString();
                        if (Files.isDirectory(path)) {
                            nodes.add(FileNode.builder()
                                    .name(path.getFileName().toString())
                                    .path(relativePath)
                                    .directory(true)
                                    .children(buildTree(rootDir, path, depth + 1))
                                    .build());
                        } else {
                            nodes.add(FileNode.builder()
                                    .name(path.getFileName().toString())
                                    .path(relativePath)
                                    .directory(false)
                                    .build());
                        }
                    });
        } catch (IOException e) {
            log.error("读取目录失败: {}", currentDir, e);
        }

        return nodes;
    }

    private static final String FILE_SUBDIR = "file";

    @Override
    public String uploadFile(Path workspaceDir, MultipartFile file) {
        // 校验文件
        if (file.isEmpty()) {
            throw new BusinessException(400, "不允许上传空文件");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new BusinessException(400, "文件名不能为空");
        }
        // 路径穿越检查：只取文件名部分
        String safeName = Path.of(originalName).getFileName().toString();
        if (safeName.contains("..") || safeName.startsWith("/")) {
            throw new BusinessException(400, "文件名不合法");
        }

        // 确保 file 子目录存在
        Path fileDir = workspaceDir.resolve(FILE_SUBDIR);
        try {
            Files.createDirectories(fileDir);
        } catch (IOException e) {
            log.error("创建文件目录失败: {}", fileDir, e);
            throw new BusinessException("创建文件目录失败: " + e.getMessage());
        }

        // 同名文件添加时间戳后缀
        Path targetPath = fileDir.resolve(safeName);
        if (Files.exists(targetPath)) {
            String nameWithoutExt = safeName;
            String ext = "";
            int dotIndex = safeName.lastIndexOf('.');
            if (dotIndex > 0) {
                nameWithoutExt = safeName.substring(0, dotIndex);
                ext = safeName.substring(dotIndex);
            }
            safeName = nameWithoutExt + "_" + System.currentTimeMillis() + ext;
            targetPath = fileDir.resolve(safeName);
        }

        // 写入文件
        try (InputStream is = file.getInputStream()) {
            Files.copy(is, targetPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("文件上传失败: {}", targetPath, e);
            throw new BusinessException("文件上传失败: " + e.getMessage());
        }

        String relativePath = FILE_SUBDIR + "/" + safeName;
        log.info("文件上传成功: workspaceDir={}, relativePath={}, size={}",
                workspaceDir, relativePath, file.getSize());
        return relativePath;
    }
}
