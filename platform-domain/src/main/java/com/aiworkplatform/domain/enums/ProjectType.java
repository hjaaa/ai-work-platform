package com.aiworkplatform.domain.enums;

import lombok.Getter;

/**
 * 项目类型枚举
 */
@Getter
public enum ProjectType {

    /** 远程 Git 仓库项目 */
    GIT("git"),

    /** 本地项目 */
    LOCAL("local");

    private final String value;

    ProjectType(String value) {
        this.value = value;
    }
}
