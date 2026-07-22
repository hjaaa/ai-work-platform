package com.aiwork.baas.data.enduser;

import com.aiwork.baas.support.PlanBContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lua 固定窗口限速(spec §12.2/§14):计数键恒有 TTL、窗口不被重设、阈值边界、fail-open。
 */
class AuthRateLimiterTest extends PlanBContainerSupport {

    StringRedisTemplate template;

    AuthRateLimiter limiter;

    @BeforeEach
    void setUp() {
        template = redisTemplate(redisConnectionFactory());
        AuthProperties properties = new AuthProperties();
        limiter = new AuthRateLimiter(template, properties);
        template.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    void counterAlwaysHasTtlFromFirstIncrement() {
        String key = AuthRateLimiter.ipKey("login", 1L, "1.2.3.4");
        AuthRateLimiter.RateProbe probe = limiter.increment(1L, key, 900);
        assertThat(probe.count()).isEqualTo(1);
        assertThat(probe.ttlSeconds()).isBetween(1L, 900L);
        assertThat(template.getExpire(key)).isBetween(1L, 900L);
    }

    @Test
    void windowIsNotResetByLaterIncrements() throws InterruptedException {
        String key = AuthRateLimiter.ipKey("login", 1L, "5.6.7.8");
        limiter.increment(1L, key, 10);
        Thread.sleep(1100);
        AuthRateLimiter.RateProbe probe = limiter.increment(1L, key, 10);
        // 第二次 INCR 不重设过期:TTL 必须已经衰减(< 窗口全长)
        assertThat(probe.count()).isEqualTo(2);
        assertThat(probe.ttlSeconds()).isLessThan(10L);
    }

    @Test
    void thresholdBoundary() {
        String key = AuthRateLimiter.emailKey("login", 1L, "abc123");
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.retryAfterIfBlocked(1L, key, 5)).isNull();
            limiter.increment(1L, key, 900);
        }
        // 第 5 次计入后,第 6 次前置检查必须拒绝并返回剩余秒数
        Long retryAfter = limiter.retryAfterIfBlocked(1L, key, 5);
        assertThat(retryAfter).isNotNull().isBetween(1L, 900L);
    }

    @Test
    void clearRemovesCounter() {
        String key = AuthRateLimiter.emailKey("login", 1L, "def456");
        limiter.increment(1L, key, 900);
        limiter.clear(key);
        assertThat(limiter.retryAfterIfBlocked(1L, key, 1)).isNull();
    }

    @Test
    void legacyKeyWithoutTtlIsHealed() {
        // 防御:历史上无 TTL 的计数键被脚本补上 TTL,不产生永久封禁
        String key = AuthRateLimiter.ipKey("signup", 1L, "9.9.9.9");
        template.opsForValue().set(key, "3");
        AuthRateLimiter.RateProbe probe = limiter.increment(1L, key, 3600);
        assertThat(probe.count()).isEqualTo(4);
        assertThat(template.getExpire(key)).isBetween(1L, 3600L);
    }

    @Test
    void redisDownIsFailOpen() {
        LettuceConnectionFactory dead = new LettuceConnectionFactory("127.0.0.1", 1);
        dead.afterPropertiesSet();
        StringRedisTemplate deadTemplate = redisTemplate(dead);
        AuthRateLimiter failOpen = new AuthRateLimiter(deadTemplate, new AuthProperties());
        assertThat(failOpen.increment(1L, AuthRateLimiter.ipKey("login", 1L, "1.1.1.1"), 900)).isNull();
        assertThat(failOpen.retryAfterIfBlocked(1L, AuthRateLimiter.ipKey("login", 1L, "1.1.1.1"), 5)).isNull();
    }

}
