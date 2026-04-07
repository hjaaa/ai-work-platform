package com.aiworkplatform.service.project;

import com.aiworkplatform.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 文件服务
 * 提供项目工作区的文件树和文件内容读取
 */
@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    // 忽略的目录和文件
    private static final Set<String> IGNORED = Set.of(
            ".git", "node_modules", "target", ".idea", ".vscode",
            ".DS_Store", "Thumbs.db"
    );

    private static final long MAX_FILE_SIZE = 1024 * 1024;
    private static final int MAX_DEPTH = 6;
    private static final int MAX_NODES = 500;
    private int nodeCount;

    /**
     * 获取项目工作区的文件树（限制深度和节点数，防止大项目阻塞）
     */
    public List<FileNode> getFileTree(Path projectDir) {
        if (!Files.exists(projectDir)) {
            return List.of();
        }
        nodeCount = 0;
        return buildTree(projectDir, projectDir, 0);
    }

    /**
     * 读取文件内容
     */
    public String readFileContent(Path projectDir, String relativePath) {
        Path filePath = projectDir.resolve(relativePath).normalize();

        // 安全检查：防止路径穿越
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
}
