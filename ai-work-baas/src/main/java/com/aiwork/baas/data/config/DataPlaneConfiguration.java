package com.aiwork.baas.data.config;

import com.aiwork.baas.data.auth.ApiKeyAuthFilter;
import com.aiwork.baas.data.auth.BaasJwtVerifier;
import com.aiwork.baas.data.cors.DataPlaneCorsFilter;
import com.aiwork.baas.data.error.DataErrorWriter;
import com.aiwork.baas.data.enduser.AuthProperties;
import com.aiwork.baas.data.error.DataPlaneErrorBoundaryFilter;
import com.aiwork.baas.mapper.BaasApiKeyMapper;
import com.aiwork.baas.mapper.BaasProjectMapper;
import com.aiwork.baas.security.key.ApiKeyGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.concurrent.Semaphore;

/**
 * 数据面装配:独立 ObjectMapper(绕开平台 Long→String/时间格式/XSS 清洗)、
 * 错误输出器、并发响应信号量。
 *
 * @author ai-work
 * @date 2026/07/21
 */
@Configuration
@EnableConfigurationProperties({ DataPlaneProperties.class, AuthProperties.class })
public class DataPlaneConfiguration {

    /**
     * 数据面专用 ObjectMapper:不注册平台任何 Module/定制;USE_BIG_DECIMAL_FOR_FLOATS
     * 保证 decimal body 的 number token 无损,FAIL_ON_TRAILING_TOKENS 保证 body 只有一个根值。
     * defaultCandidate=false 是隔离关键:不让 Boot 默认 ObjectMapper 自动配置回退,也不参与
     * JacksonConfiguration 的 ObjectProvider<ObjectMapper> 默认候选选择。
     * @return 数据面 ObjectMapper
     */
    @Bean(name = "dataPlaneObjectMapper", defaultCandidate = false)
    public ObjectMapper dataPlaneObjectMapper() {
        return JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    }

    @Bean
    public DataErrorWriter dataErrorWriter(@Qualifier("dataPlaneObjectMapper") ObjectMapper dataPlaneObjectMapper) {
        return new DataErrorWriter(dataPlaneObjectMapper);
    }

    @Bean
    public Clock dataPlaneClock() {
        return Clock.systemUTC();
    }

    /**
     * 并发响应构建信号量(spec §13:响应缓冲堆内存上限 = 许可数 × 响应体上限)。
     * @param properties 数据面配置
     * @return 信号量
     */
    @Bean("dataResponsePermits")
    public Semaphore dataResponsePermits(DataPlaneProperties properties) {
        return new Semaphore(properties.getResponsePermits());
    }

    @Bean
    public FilterRegistrationBean<DataPlaneErrorBoundaryFilter> dataPlaneErrorBoundaryFilterRegistration(
            DataErrorWriter errorWriter) {
        FilterRegistrationBean<DataPlaneErrorBoundaryFilter> registration = new FilterRegistrationBean<>(
                new DataPlaneErrorBoundaryFilter(errorWriter));
        registration.addUrlPatterns("/data/*");
        registration.setOrder(0);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<DataPlaneCorsFilter> dataPlaneCorsFilterRegistration(
            BaasProjectMapper projectMapper,
            @Qualifier("dataPlaneObjectMapper") ObjectMapper dataPlaneObjectMapper,
            DataPlaneProperties properties) {
        FilterRegistrationBean<DataPlaneCorsFilter> registration = new FilterRegistrationBean<>(
                new DataPlaneCorsFilter(projectMapper, dataPlaneObjectMapper, properties));
        registration.addUrlPatterns("/data/*");
        registration.setOrder(10);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilterRegistration(BaasApiKeyMapper apiKeyMapper,
            BaasProjectMapper projectMapper, ApiKeyGenerator keyGenerator, BaasJwtVerifier jwtVerifier,
            DataErrorWriter errorWriter, DataPlaneProperties properties) {
        FilterRegistrationBean<ApiKeyAuthFilter> registration = new FilterRegistrationBean<>(
                new ApiKeyAuthFilter(apiKeyMapper, projectMapper, keyGenerator, jwtVerifier, errorWriter,
                        properties));
        registration.addUrlPatterns("/data/*");
        registration.setOrder(20);
        return registration;
    }

}
