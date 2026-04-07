package com.aiworkplatform.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.aiworkplatform")
public class AiWorkPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiWorkPlatformApplication.class, args);
    }
}
