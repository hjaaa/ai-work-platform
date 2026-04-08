package com.aiworkplatform.service.project;

import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;

/**
 * 文件服务接口
 */
public interface FileService {

    /**
     * 获取项目工作区的文件树
     */
    List<FileNode> getFileTree(Path projectDir);

    /**
     * 读取文件内容
     */
    String readFileContent(Path projectDir, String relativePath);

    /**
     * 上传文件到项目工作区的 file/ 子目录
     *
     * @param workspaceDir 项目工作区根目录
     * @param file         上传的文件
     * @return 相对于 workspaceDir 的文件路径（如 "file/xxx.txt"）
     */
    String uploadFile(Path workspaceDir, MultipartFile file);
}
