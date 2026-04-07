package com.aiworkplatform.domain.enums;

import lombok.Getter;

@Getter
public enum GenerationStatus {
    RUNNING("running"),
    SUCCESS("success"),
    FAILED("failed");

    private final String value;

    GenerationStatus(String value) {
        this.value = value;
    }
}
