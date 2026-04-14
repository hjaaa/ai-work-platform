package com.aiworkplatform.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 项目实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("project")
public class Project extends BaseEntity {

    /** 项目标识 */
    private String projectId;

    /** 项目类型: git-远程仓库, local-本地项目 */
    private String projectType;

    /** 项目名称 */
    private String name;

    /** 项目描述 */
    private String description;

    /** Git 仓库地址 */
    private String gitUrl;

    /** 默认分支 */
    private String defaultBranch;

    /** 工作区路径（系统托管目录，用于存储上传文件等） */
    private String workspacePath;

    /** 本地项目路径（仅 local 类型使用） */
    private String localPath;

    /** Git clone 的代码存储目录 */
    private String codePath;

    /** Git clone 失败时的错误信息 */
    private String cloneErrorMessage;

    /** 状态: creating/active/deploying/deployed/failed */
    private String status;

    /** 部署后的访问地址 */
    private String deployUrl;
}
