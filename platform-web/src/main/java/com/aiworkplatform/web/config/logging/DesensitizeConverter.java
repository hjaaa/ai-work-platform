package com.aiworkplatform.web.config.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Pattern;

/**
 * Logback 日志脱敏转换器
 * 在 logback-spring.xml 中通过 %desensitize(%msg) 使用
 *
 * 脱敏规则（参考 context/team/logging-standards.md）：
 * - 手机号: 138****1234
 * - 身份证: 320106****1234
 * - 银行卡: ****5678
 */
public class DesensitizeConverter extends ClassicConverter {

    // 手机号：1开头的11位数字
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(1[3-9]\\d)\\d{4}(\\d{4})");

    // 身份证：18位（最后一位可能是X）
    private static final Pattern ID_CARD_PATTERN = Pattern.compile(
            "(\\d{6})\\d{8}(\\d{3}[0-9Xx])");

    // 银行卡：16-19位数字
    private static final Pattern BANK_CARD_PATTERN = Pattern.compile(
            "\\d{12,15}(\\d{4})");

    @Override
    public String convert(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        if (message == null || message.isEmpty()) {
            return message;
        }
        return desensitize(message);
    }

    static String desensitize(String text) {
        // 先处理身份证（18位，避免被手机号规则误匹配）
        text = ID_CARD_PATTERN.matcher(text).replaceAll("$1****$2");
        // 再处理手机号
        text = PHONE_PATTERN.matcher(text).replaceAll("$1****$2");
        // 最后处理银行卡
        text = BANK_CARD_PATTERN.matcher(text).replaceAll("****$1");
        return text;
    }
}
