package com.aiworkplatform.domain.enums;

import lombok.Getter;

@Getter
public enum GenerationType {
    CODE("code"),
    PRD("prd");

    private final String value;

    GenerationType(String value) {
        this.value = value;
    }
}
