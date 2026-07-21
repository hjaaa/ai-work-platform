package com.aiwork.baas;

import com.aiwork.baas.security.CurrentUserProvider;
import com.aiwork.baas.security.TestCurrentUserProvider;
import com.aiwork.baas.security.crypto.BaasCryptoService;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;

/**
 * 数据面集成测试装配:在 Plan B 测试装配基础上追加
 * com.aiwork.baas.data 包(controller/filter/executor)。
 *
 * @author ai-work
 * @date 2026/07/21
 */
@SpringBootApplication(scanBasePackages = { "com.aiwork.baas.service", "com.aiwork.baas.provision",
        "com.aiwork.baas.datasource", "com.aiwork.baas.security.key", "com.aiwork.baas.ddl",
        "com.aiwork.baas.data" })
@MapperScan("com.aiwork.baas.mapper")
public class DataPlaneTestApplication {

    @Bean
    public BaasCryptoService baasCryptoService() {
        return new BaasCryptoService(Map.of("k1", Base64.getEncoder().encodeToString(new byte[32])), "k1");
    }

    @Bean
    public CurrentUserProvider currentUserProvider() {
        return new TestCurrentUserProvider();
    }

    @Bean
    @Primary
    public Clock testClock() {
        return Clock.fixed(Instant.now().truncatedTo(ChronoUnit.SECONDS), ZoneOffset.UTC);
    }

}
