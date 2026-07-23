package com.aiwork.baas.data.enduser;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 终端用户 Auth 配置(spec §7.2/§7.6/§12.2,均可配)。
 *
 * @author ai-work
 * @date 2026/07/22
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "baas.auth")
public class AuthProperties {

    /** access JWT TTL 秒数,spec §7.2:1 小时。 */
    private long accessTtlSeconds = 3600;

    /** refresh token TTL 天数,spec §7.2:30。 */
    private long refreshTtlDays = 30;

    /** refresh 重放 grace 秒数,spec §7.2:10。 */
    private long reuseGraceSeconds = 10;

    /** 可信代理(IP 或 IPv4 CIDR),spec §12.2:默认空,Boot 形态恒空。 */
    private List<String> trustedProxies = List.of();

    /** login 失败/改密错密码的邮箱维度阈值,spec §12.2:5 次 / 15 分钟。 */
    private long loginEmailLimit = 5;

    private long loginEmailWindowSeconds = 900;

    /** login 失败/改密错密码的 IP 维度阈值,spec §12.2:30 次 / 15 分钟。 */
    private long loginIpLimit = 30;

    private long loginIpWindowSeconds = 900;

    /** signup 的 IP 维度阈值(无论成败),spec §12.2:10 次 / 1 小时。 */
    private long signupIpLimit = 10;

    private long signupIpWindowSeconds = 3600;

    /** Redis fail-open 时 error 日志限频秒数(spec §12.2:防日志风暴)。 */
    private long failOpenLogThrottleSeconds = 60;

    /** 清理任务间隔与首延迟毫秒数(spec §7.6)。 */
    private long cleanupIntervalMillis = 300000;

    private long cleanupInitialDelayMillis = 60000;

    /** 清理任务单批 delete/update 行数上限(spec §7.6/§13):分批独立提交保证有界推进,防整体回滚丢进度。 */
    private int cleanupBatchSize = 1000;

}
