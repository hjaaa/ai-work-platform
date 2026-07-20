/*
 *
 *      Copyright (c) 2018-2026, lengleng All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 * Neither the name of the pig4cloud.com developer nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 * Author: lengleng (wangiegie@gmail.com)
 *
 */

package com.aiwork;

import com.baomidou.dynamic.datasource.creator.DataSourceCreator;
import com.baomidou.dynamic.datasource.creator.druid.DruidDataSourceCreator;
import com.aiwork.baas.config.BaasSchedulingConfiguration;
import com.aiwork.baas.controller.StudioTableController;
import com.aiwork.baas.security.crypto.MasterKeySource;
import com.aiwork.baas.service.SystemTableMigrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.quartz.autoconfigure.SchedulerFactoryBeanCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * boot profile 对 BaaS 关键 Bean 与 Studio 路由的真实应用上下文冒烟测试。
 *
 * @author ai-work
 * @date 2026/07/20
 */
@SpringBootTest(classes = { AiWorkBootApplication.class, BaasBootRouteSmokeTest.ExternalIsolation.class },
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = { "spring.profiles.active=test", "spring.config.import=",
                "spring.autoconfigure.exclude=com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourceAutoConfiguration,com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.nacos.config.enabled=false", "spring.cloud.service-registry.auto-registration.enabled=false",
                "spring.quartz.auto-startup=false", "baas.migration.startup-scan-enabled=false",
                "baas.provisioner.url=jdbc:mysql://127.0.0.1:1/unused?connectTimeout=100&socketTimeout=100",
                "baas.provisioner.username=test", "baas.provisioner.password=test",
                "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1",
                "security.encodeKey=aiwork2026aiwork", "jasypt.encryptor.password=aiwork" })
class BaasBootRouteSmokeTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping requestMappings;

    @Test
    void realBootContextMountsBaasBeansSchedulerAndRoutes() {
        assertThat(applicationContext.getBean(StudioTableController.class)).isNotNull();
        assertThat(applicationContext.getBean(BaasSchedulingConfiguration.class)).isNotNull();
        assertThat(applicationContext.getBean(TaskScheduler.class)).isNotNull();
        assertThat(applicationContext.getBean(SystemTableMigrationService.class)).isNotNull();
        assertThat(requestMappings.getHandlerMethods().entrySet()).anySatisfy(entry -> {
            assertThat(entry.getKey().getPatternValues()).contains("/studio/projects/{ref}/tables");
            assertThat(entry.getValue().getBeanType()).isEqualTo(StudioTableController.class);
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ExternalIsolation {

        @Bean
        static BeanFactoryPostProcessor disableQuartzDatabaseInitializer() {
            return beanFactory -> {
                BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
                if (registry.containsBeanDefinition("aiWorkInitQuartzJob")) {
                    registry.removeBeanDefinition("aiWorkInitQuartzJob");
                }
            };
        }

        @Bean
        SchedulerFactoryBeanCustomizer disableQuartzStartup() {
            return schedulerFactoryBean -> schedulerFactoryBean.setAutoStartup(false);
        }

        @Bean
        @Primary
        DataSource testDataSource() {
            DatabaseMetaData metadata = proxy(DatabaseMetaData.class, (methodName, returnType) -> switch (methodName) {
                case "getDatabaseProductName" -> "MySQL";
                case "getDatabaseProductVersion" -> "8.4";
                default -> defaultValue(returnType);
            });
            Connection connection = proxy(Connection.class,
                    (methodName, returnType) -> "getMetaData".equals(methodName) ? metadata : defaultValue(returnType));
            return proxy(DataSource.class,
                    (methodName, returnType) -> "getConnection".equals(methodName) ? connection
                            : defaultValue(returnType));
        }

        @Bean
        @Primary
        DataSourceCreator testDataSourceCreator(DataSource testDataSource) {
            return proxy(DataSourceCreator.class, (methodName, returnType) -> switch (methodName) {
                case "createDataSource" -> testDataSource;
                case "support" -> true;
                default -> defaultValue(returnType);
            });
        }

        @Bean
        DruidDataSourceCreator testDruidDataSourceCreator() {
            return new DruidDataSourceCreator();
        }

        @Bean
        @Primary
        MasterKeySource testMasterKeySource() {
            return new MasterKeySource() {
                @Override
                public Map<String, String> masterKeys() {
                    return Map.of("test", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
                }

                @Override
                public String activeKeyId() {
                    return "test";
                }
            };
        }

        private static <T> T proxy(Class<T> type, StubMethod stubMethod) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
                    (proxy, method, args) -> stubMethod.invoke(method.getName(), method.getReturnType())));
        }

        private static Object defaultValue(Class<?> returnType) {
            if (!returnType.isPrimitive() || returnType == void.class) {
                return null;
            }
            if (returnType == boolean.class) {
                return false;
            }
            if (returnType == char.class) {
                return '\0';
            }
            if (returnType == byte.class) {
                return (byte) 0;
            }
            if (returnType == short.class) {
                return (short) 0;
            }
            if (returnType == int.class) {
                return 0;
            }
            if (returnType == long.class) {
                return 0L;
            }
            if (returnType == float.class) {
                return 0F;
            }
            return 0D;
        }

        @FunctionalInterface
        private interface StubMethod {

            Object invoke(String methodName, Class<?> returnType);

        }

    }

}
