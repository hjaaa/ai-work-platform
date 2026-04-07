package com.aiworkplatform.web.config.logging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DesensitizeConverterTest {

    @Test
    void should_desensitizePhone_when_phoneNumberPresent() {
        String input = "用户手机号 13812345678 已验证";
        String result = DesensitizeConverter.desensitize(input);
        assertEquals("用户手机号 138****5678 已验证", result);
    }

    @Test
    void should_desensitizeIdCard_when_idCardPresent() {
        String input = "身份证号 320106199901011234 已校验";
        String result = DesensitizeConverter.desensitize(input);
        assertEquals("身份证号 320106****1234 已校验", result);
    }

    @Test
    void should_desensitizeBankCard_when_bankCardPresent() {
        String input = "银行卡 6222021234567890 扣款成功";
        String result = DesensitizeConverter.desensitize(input);
        assertEquals("银行卡 ****7890 扣款成功", result);
    }

    @Test
    void should_notChange_when_noSensitiveData() {
        String input = "普通日志内容 userId=123 projectId=proj-001";
        String result = DesensitizeConverter.desensitize(input);
        assertEquals(input, result);
    }

    @Test
    void should_desensitizeMultiple_when_multipleSensitiveData() {
        String input = "用户 13912345678 身份证 320106199901011234";
        String result = DesensitizeConverter.desensitize(input);
        assertTrue(result.contains("139****5678"));
        assertTrue(result.contains("320106****1234"));
    }
}
