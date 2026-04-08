package com.aiworkplatform.web.controller;

import com.aiworkplatform.common.result.Result;
import com.aiworkplatform.domain.entity.ChatThread;
import com.aiworkplatform.domain.entity.Conversation;
import com.aiworkplatform.domain.entity.Project;
import com.aiworkplatform.service.chat.ChatService;
import com.aiworkplatform.service.project.FileService;
import com.aiworkplatform.service.project.ProjectService;
import com.aiworkplatform.service.thread.ChatThreadService;
import com.aiworkplatform.web.dto.UpdateThreadRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import java.util.List;

/**
 * 对话线程控制器
 * 管理项目下的对话线程及线程内的消息
 */
@RestController
@RequestMapping("/api")
public class ChatThreadController {

    private final ChatThreadService chatThreadService;
    private final ChatService chatService;
    private final FileService fileService;
    private final ProjectService projectService;

    public ChatThreadController(ChatThreadService chatThreadService,
                                ChatService chatService,
                                FileService fileService,
                                ProjectService projectService) {
        this.chatThreadService = chatThreadService;
        this.chatService = chatService;
        this.fileService = fileService;
        this.projectService = projectService;
    }

    /**
     * 创建新线程
     */
    @PostMapping("/projects/{projectId}/threads")
    public Result<ChatThread> createThread(@PathVariable String projectId) {
        return Result.success(chatThreadService.createThread(projectId));
    }

    /**
     * 获取项目下的线程列表（按最后活跃时间倒序）
     */
    @GetMapping("/projects/{projectId}/threads")
    public Result<List<ChatThread>> listThreads(@PathVariable String projectId) {
        return Result.success(chatThreadService.listByProject(projectId));
    }

    /**
     * 更新线程标题
     */
    @PutMapping("/threads/{threadId}")
    public Result<Void> updateThread(@PathVariable String threadId,
                                     @RequestBody @Valid UpdateThreadRequest request) {
        chatThreadService.updateTitle(threadId, request.getTitle());
        return Result.success();
    }

    /**
     * 删除线程（逻辑删除线程及其消息）
     */
    @DeleteMapping("/threads/{threadId}")
    public Result<Void> deleteThread(@PathVariable String threadId) {
        chatThreadService.deleteThread(threadId);
        return Result.success();
    }

    /**
     * 获取线程的消息列表
     */
    @GetMapping("/threads/{threadId}/messages")
    public Result<List<Conversation>> getMessages(@PathVariable String threadId) {
        return Result.success(chatService.getHistoryByThread(threadId));
    }

    /**
     * 在线程中发送消息
     *
     * @param filePaths 可选，已上传文件的相对路径列表（由 upload 接口返回）
     */
    @PostMapping("/threads/{threadId}/chat")
    public Result<Void> chat(@PathVariable String threadId,
                             @RequestParam @NotBlank String message,
                             @RequestParam(required = false) List<String> filePaths) {
        chatService.handleThreadMessage(threadId, message, filePaths);
        return Result.success();
    }

    /**
     * 上传文件到线程对应项目的工作区 file/ 目录
     *
     * @return 文件相对路径（如 "file/xxx.txt"），后续发消息时附带此路径
     */
    @PostMapping("/threads/{threadId}/upload")
    public Result<String> uploadFile(@PathVariable String threadId,
                                     @RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return Result.fail(400, "请选择文件");
        }
        ChatThread thread = chatThreadService.getByThreadId(threadId);
        Project project = projectService.getByProjectId(thread.getProjectId());
        String relativePath = fileService.uploadFile(
                java.nio.file.Path.of(project.getWorkspacePath()), file);
        return Result.success(relativePath);
    }

    /**
     * 取消线程中正在进行的 AI 生成
     */
    @PostMapping("/threads/{threadId}/cancel")
    public Result<Void> cancel(@PathVariable String threadId) {
        chatService.cancelThread(threadId);
        return Result.success();
    }
}
