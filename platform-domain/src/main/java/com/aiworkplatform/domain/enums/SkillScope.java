package com.aiworkplatform.domain.enums;

import lombok.Getter;

/**
 * Skill 作用域枚举
 */
@Getter
public enum SkillScope {

    /** 个人级，对所有项目可用，文件存储在 ~/.claude/skills/ */
    PERSONAL("PERSONAL"),

    /** 项目级，仅关联项目可用，文件存储在 {project.codePath}/.claude/skills/ */
    PROJECT("PROJECT"),

    /** 系统级，平台内置，不可修改/删除/通过 API 创建，不写磁盘文件 */
    SYSTEM("SYSTEM");

    private final String value;

    SkillScope(String value) {
        this.value = value;
    }
}
