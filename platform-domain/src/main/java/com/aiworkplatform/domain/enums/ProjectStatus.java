package com.aiworkplatform.domain.enums;

import lombok.Getter;

@Getter
public enum ProjectStatus {
    CREATING("creating"),
    ACTIVE("active"),
    DEPLOYING("deploying"),
    DEPLOYED("deployed"),
    FAILED("failed");

    private final String value;

    ProjectStatus(String value) {
        this.value = value;
    }
}
