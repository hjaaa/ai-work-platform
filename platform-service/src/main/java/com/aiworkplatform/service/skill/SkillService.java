package com.aiworkplatform.service.skill;

import com.aiworkplatform.domain.entity.Skill;
import com.aiworkplatform.domain.enums.SkillScope;

import java.util.List;

/**
 * Skill 服务接口
 */
public interface SkillService {

    /**
     * 创建 Skill，并同步写入磁盘文件。
     * <p>
     * scope=PROJECT 时要求：projectId 非空，且关联项目 codePath 非空。
     * 文件操作先于 DB 写入，文件失败直接抛异常。
     *
     * @param name          Skill 名称
     * @param description   Skill 描述（markdown 内容）
     * @param scope         作用域
     * @param projectId     关联项目 ID（scope=PROJECT 时必填）
     * @param createdBy     创建人
     * @param personalSkillBaseDir 个人 skill 的磁盘基础目录（scope=PERSONAL 时使用，
     *                             实际文件写入 {personalSkillBaseDir}/.claude/skills/）
     * @return 创建成功的 Skill 实体
     */
    Skill createSkill(String name, String description, SkillScope scope,
                      String projectId, String createdBy, String personalSkillBaseDir);

    /**
     * 修改 Skill，并同步覆盖磁盘文件。
     *
     * @param skillId              业务主键
     * @param name                 新名称
     * @param description          新描述
     * @param personalSkillBaseDir 个人 skill 的磁盘基础目录
     */
    void updateSkill(String skillId, String name, String description, String personalSkillBaseDir);

    /**
     * 删除 Skill：DB 软删除 + 磁盘文件物理删除（文件不存在时静默忽略）。
     *
     * @param skillId              业务主键
     * @param personalSkillBaseDir 个人 skill 的磁盘基础目录
     */
    void deleteSkill(String skillId, String personalSkillBaseDir);

    /**
     * 查询 Skill 列表。
     *
     * @param scope          作用域过滤（null 表示不过滤）
     * @param projectId      项目 ID 过滤（scope=PROJECT 时使用）
     * @param includeSystem  是否包含系统级 skill（scope 为 null 时生效，默认排除）
     * @return Skill 列表
     */
    List<Skill> listSkills(SkillScope scope, String projectId, boolean includeSystem);
}
