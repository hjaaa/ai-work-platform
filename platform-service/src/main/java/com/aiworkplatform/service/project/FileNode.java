package com.aiworkplatform.service.project;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 文件树节点
 */
@Data
@Builder
public class FileNode {

    private String name;
    private String path;
    private boolean directory;
    private List<FileNode> children;
}
