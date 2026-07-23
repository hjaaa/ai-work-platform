package com.aiwork.baas.data.enduser;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 客户端 IP 判定(spec §12.2):不得无条件信任 X-Forwarded-For——
 * 仅当 remoteAddr 属于可信代理列表(IP 或 IPv4 CIDR)时读取 XFF,取最右条目;
 * 否则恒用 remoteAddr(Boot 形态默认列表为空)。
 *
 * @author ai-work
 * @date 2026/07/22
 */
@Component
public class ClientIpResolver {

    private final AuthProperties properties;

    public ClientIpResolver(AuthProperties properties) {
        this.properties = properties;
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return remoteAddr;
        }
        String[] entries = forwardedFor.split(",");
        for (int i = entries.length - 1; i >= 0; i--) {
            String candidate = entries[i].trim();
            if (!candidate.isEmpty()) {
                return candidate;
            }
        }
        return remoteAddr;
    }

    private boolean isTrustedProxy(String remoteAddr) {
        for (String entry : properties.getTrustedProxies()) {
            String trimmed = entry == null ? "" : entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.equals(remoteAddr)) {
                return true;
            }
            if (trimmed.contains("/") && ipv4CidrContains(trimmed, remoteAddr)) {
                return true;
            }
        }
        return false;
    }

    private static boolean ipv4CidrContains(String cidr, String address) {
        long ip = ipv4ToLong(address);
        if (ip < 0) {
            return false;
        }
        String[] parts = cidr.split("/", 2);
        long network = ipv4ToLong(parts[0]);
        if (network < 0) {
            return false;
        }
        int prefix;
        try {
            prefix = Integer.parseInt(parts[1]);
        }
        catch (NumberFormatException exception) {
            return false;
        }
        if (prefix < 0 || prefix > 32) {
            return false;
        }
        long mask = prefix == 0 ? 0L : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        return (ip & mask) == (network & mask);
    }

    private static long ipv4ToLong(String address) {
        String[] octets = address.split("\\.");
        if (octets.length != 4) {
            return -1;
        }
        long value = 0;
        for (String octet : octets) {
            int part;
            try {
                part = Integer.parseInt(octet);
            }
            catch (NumberFormatException exception) {
                return -1;
            }
            if (part < 0 || part > 255) {
                return -1;
            }
            value = (value << 8) | part;
        }
        return value;
    }

}
