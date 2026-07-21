package com.aiwork.baas.data.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 数据面资源限制与协议参数(spec §7.1/§7.5/§12.2/§13,均可配)。
 *
 * @author ai-work
 * @date 2026/07/21
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "baas.data")
public class DataPlaneProperties {

    /** 请求体上限(字节),spec §13:1 MB。 */
    private long bodyMaxBytes = 1_048_576L;

    /** 响应体上限(字节),spec §13:16 MiB。 */
    private long responseMaxBytes = 16L * 1024 * 1024;

    /** 并发响应构建信号量许可数,spec §13:默认 8。 */
    private int responsePermits = 8;

    /** representation 行数上限,spec §7.1:1000。 */
    private int representationMaxRows = 1000;

    /** 批量插入行数上限,spec §13:1000。 */
    private int batchMaxRows = 1000;

    /** 过滤条件数量上限,spec §13:20。 */
    private int maxFilters = 20;

    /** SQL 执行超时秒数,spec §13:5。 */
    private int queryTimeoutSeconds = 5;

    /** 默认分页大小,spec §7.1:100。 */
    private int defaultLimit = 100;

    /** 分页上限,spec §7.1:1000。 */
    private int maxLimit = 1000;

    /** 服务端游标 fetchSize,spec §7.5:100。 */
    private int fetchSize = 100;

    /** JWT 时钟偏差容忍秒数,spec §7.5:60。 */
    private long jwtClockSkewSeconds = 60;

    /** JWT 最大 TTL 秒数,spec §7.5:1 小时。 */
    private long jwtMaxTtlSeconds = 3600;

    /** api key last_used_time 节流秒数,spec §7.5:每 key 每分钟至多一次。 */
    private long keyTouchThrottleSeconds = 60;

    /** CORS 预检 Max-Age 秒数,spec §12.2:默认 3600。 */
    private long corsMaxAgeSeconds = 3600;

}
