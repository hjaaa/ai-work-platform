package com.aiworkplatform.service.project;

import com.aiworkplatform.common.exception.BusinessException;
import com.aiworkplatform.service.project.impl.FileServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileUploadTest {

    private FileServiceImpl fileService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        fileService = new FileServiceImpl();
    }

    // --- 正常路径 ---

    @Test
    void should_upload_file_to_file_directory() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.txt", "text/plain", "hello world".getBytes());

        String relativePath = fileService.uploadFile(tempDir, file);

        // 文件应存在于 workspacePath/file/ 下
        Path uploaded = tempDir.resolve(relativePath);
        assertTrue(Files.exists(uploaded));
        assertEquals("hello world", Files.readString(uploaded));
        assertTrue(relativePath.startsWith("file/"));
    }

    @Test
    void should_create_file_directory_if_not_exists() throws IOException {
        // file 子目录不存在
        Path fileDir = tempDir.resolve("file");
        assertFalse(Files.exists(fileDir));

        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf", "pdf-content".getBytes());

        fileService.uploadFile(tempDir, file);

        // 目录应被自动创建
        assertTrue(Files.isDirectory(fileDir));
    }

    @Test
    void should_handle_duplicate_filename_without_overwrite() throws IOException {
        MockMultipartFile file1 = new MockMultipartFile(
                "file", "same.txt", "text/plain", "content-1".getBytes());
        MockMultipartFile file2 = new MockMultipartFile(
                "file", "same.txt", "text/plain", "content-2".getBytes());

        String path1 = fileService.uploadFile(tempDir, file1);
        String path2 = fileService.uploadFile(tempDir, file2);

        // 两个文件都存在，路径不同
        assertNotEquals(path1, path2);
        assertTrue(Files.exists(tempDir.resolve(path1)));
        assertTrue(Files.exists(tempDir.resolve(path2)));
        // 原文件内容不被覆盖
        assertEquals("content-1", Files.readString(tempDir.resolve(path1)));
        assertEquals("content-2", Files.readString(tempDir.resolve(path2)));
    }

    // --- 异常路径 ---

    @Test
    void should_reject_empty_file() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.txt", "text/plain", new byte[0]);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fileService.uploadFile(tempDir, file));
        assertTrue(ex.getMessage().contains("空文件"));
    }

    @Test
    void should_reject_null_filename() {
        MockMultipartFile file = new MockMultipartFile(
                "file", null, "text/plain", "content".getBytes());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fileService.uploadFile(tempDir, file));
        assertTrue(ex.getMessage().contains("文件名"));
    }

    @Test
    void should_sanitize_path_traversal_filename() throws IOException {
        // "../../../etc/passwd" 经过 Path.getFileName() 处理后只保留 "passwd"
        MockMultipartFile file = new MockMultipartFile(
                "file", "../../../etc/passwd", "text/plain", "safe-content".getBytes());

        String relativePath = fileService.uploadFile(tempDir, file);

        // 文件应存储为 file/passwd，不会逃逸出 file 目录
        assertEquals("file/passwd", relativePath);
        Path uploaded = tempDir.resolve(relativePath);
        assertTrue(Files.exists(uploaded));
        assertTrue(uploaded.startsWith(tempDir.resolve("file")));
    }
}
