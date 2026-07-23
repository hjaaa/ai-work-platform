package com.aiwork.baas.data.enduser;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Auth 防暴力固定窗口限速(spec §12.2):单 Lua 脚本原子完成
 * 「INCR + 计数 0→1 时 EXPIRE + 返回计数与剩余 TTL」;禁止两条命令分步执行。
 * Redis 不可用时 fail-open:方法返回 null,调用方放行;error 日志按项目限频防风暴。
 *
 * @author ai-work
 * @date 2026/07/22
 */
@Slf4j
@Component
public class AuthRateLimiter {

    public record RateProbe(long count, long ttlSeconds) {
    }

    private static final String INCREMENT_LUA = """
            local c = redis.call('INCR', KEYS[1])
            if c == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            local t = redis.call('TTL', KEYS[1])
            if t < 0 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
              t = tonumber(ARGV[1])
            end
            return {c, t}
            """;

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> INCREMENT_SCRIPT = new DefaultRedisScript<>(INCREMENT_LUA,
            List.class);

    /** 守卫式 DECR:仅当键存在且计数 > 0 才递减,避免退还预留时把计数打成负值或创建无 TTL 的孤儿键。 */
    private static final String DECREMENT_LUA = """
            local v = redis.call('GET', KEYS[1])
            if v and tonumber(v) > 0 then
              return redis.call('DECR', KEYS[1])
            end
            return 0
            """;

    private static final DefaultRedisScript<Long> DECREMENT_SCRIPT = new DefaultRedisScript<>(DECREMENT_LUA,
            Long.class);

    private final StringRedisTemplate redisTemplate;

    private final AuthProperties properties;

    private final ConcurrentHashMap<Long, Long> failOpenLogMillis = new ConcurrentHashMap<>();

    public AuthRateLimiter(StringRedisTemplate redisTemplate, AuthProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public static String emailKey(String scope, Long projectId, String emailSha256Hex) {
        return "baas:auth:rl:" + scope + ":email:" + projectId + ":" + emailSha256Hex;
    }

    public static String ipKey(String scope, Long projectId, String ip) {
        return "baas:auth:rl:" + scope + ":ip:" + projectId + ":" + ip;
    }

    /**
     * 原子计数;Redis 故障返回 null(fail-open)。
     * @param projectId 项目 ID(仅用于 fail-open 日志限频)
     * @param key 完整 Redis 键
     * @param windowSeconds 窗口秒数
     * @return 计数与剩余 TTL;Redis 不可用时 null
     */
    public RateProbe increment(Long projectId, String key, long windowSeconds) {
        try {
            List<?> result = redisTemplate.execute(INCREMENT_SCRIPT, List.of(key), String.valueOf(windowSeconds));
            if (result == null || result.size() != 2) {
                return null;
            }
            return new RateProbe(((Number) result.get(0)).longValue(), ((Number) result.get(1)).longValue());
        }
        catch (Exception exception) {
            logFailOpen(projectId, exception);
            return null;
        }
    }

    /**
     * 前置阈值检查:计数已达 limit 返回剩余秒数(至少 1),否则 null;Redis 故障 null(fail-open)。
     * @param projectId 项目 ID
     * @param key 完整 Redis 键
     * @param limit 阈值
     * @return Retry-After 秒数或 null
     */
    public Long retryAfterIfBlocked(Long projectId, String key, long limit) {
        try {
            String value = redisTemplate.opsForValue().get(key);
            if (value == null || Long.parseLong(value) < limit) {
                return null;
            }
            Long ttl = redisTemplate.getExpire(key);
            return ttl == null || ttl < 1 ? 1L : ttl;
        }
        catch (NumberFormatException exception) {
            return null;
        }
        catch (Exception exception) {
            logFailOpen(projectId, exception);
            return null;
        }
    }

    public void clear(String key) {
        try {
            redisTemplate.delete(key);
        }
        catch (Exception exception) {
            logFailOpen(null, exception);
        }
    }

    /**
     * 退还一次预留计数(守卫式 DECR,不低于 0);Redis 故障 best-effort 忽略。
     * @param projectId 项目 ID(仅用于 fail-open 日志限频)
     * @param key 完整 Redis 键
     */
    public void decrement(Long projectId, String key) {
        try {
            redisTemplate.execute(DECREMENT_SCRIPT, List.of(key));
        }
        catch (Exception exception) {
            logFailOpen(projectId, exception);
        }
    }

    private void logFailOpen(Long projectId, Exception exception) {
        long now = System.currentTimeMillis();
        long throttleMillis = properties.getFailOpenLogThrottleSeconds() * 1000;
        Long throttleKey = projectId == null ? -1L : projectId;
        Long last = failOpenLogMillis.get(throttleKey);
        if (last != null && now - last < throttleMillis) {
            return;
        }
        if (last == null ? failOpenLogMillis.putIfAbsent(throttleKey, now) != null
                : !failOpenLogMillis.replace(throttleKey, last, now)) {
            return;
        }
        log.error("auth rate limiter fail-open (redis unavailable) projectId={} errorType={}", projectId,
                exception.getClass().getSimpleName());
    }

}
