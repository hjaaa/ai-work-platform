package com.aiworkplatform.domain.enums;

import lombok.Getter;

@Getter
public enum DeploymentStatus {
    BUILDING("building"),
    PUSHING("pushing"),
    DEPLOYING("deploying"),
    RUNNING("running"),
    FAILED("failed"),
    ROLLED_BACK("rolled_back");

    private final String value;

    DeploymentStatus(String value) {
        this.value = value;
    }
}
