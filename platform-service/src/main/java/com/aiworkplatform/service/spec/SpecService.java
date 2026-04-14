package com.aiworkplatform.service.spec;

import com.aiworkplatform.domain.entity.DevSpec;
import com.aiworkplatform.domain.entity.SpecProjectLink;

import java.util.List;

/**
 * 开发规范服务接口
 */
public interface SpecService {

    /**
     * 创建规范。
     *
     * @param name     规范名称（非空）
     * @param specType 规范类型：UI / FRONTEND / BACKEND
     * @param content  规范内容（Markdown）
     * @param createdBy 创建人
     * @return 创建成功的规范实体
     */
    DevSpec createSpec(String name, String specType, String content, String createdBy);

    /**
     * 更新规范名称、类型和内容。
     *
     * @param specId   业务主键
     * @param name     新名称
     * @param specType 新类型
     * @param content  新内容
     */
    void updateSpec(String specId, String name, String specType, String content);

    /**
     * 删除规范（有未解除关联时拒绝删除）。
     *
     * @param specId 业务主键
     */
    void deleteSpec(String specId);

    /**
     * 查询规范列表。
     *
     * @return 全部规范列表
     */
    List<DevSpec> listSpecs();

    /**
     * 将规范关联到项目：写入磁盘 + 注入 CLAUDE.md + 创建关联记录（幂等）。
     * <p>
     * 执行顺序：先写磁盘，成功后写 DB；文件失败直接抛异常阻止 DB 提交。
     *
     * @param specId    规范业务主键
     * @param projectId 项目业务主键
     */
    void linkToProject(String specId, String projectId);

    /**
     * 解除规范与项目的关联：删除磁盘文件 + 移除 CLAUDE.md 引用行 + 删除关联记录。
     * <p>
     * 关联不存在时静默返回。
     *
     * @param specId    规范业务主键
     * @param projectId 项目业务主键
     */
    void unlinkFromProject(String specId, String projectId);

    /**
     * 查询指定规范已关联的项目列表。
     *
     * @param specId 规范业务主键
     * @return 关联记录列表
     */
    List<SpecProjectLink> listLinkedProjects(String specId);
}
