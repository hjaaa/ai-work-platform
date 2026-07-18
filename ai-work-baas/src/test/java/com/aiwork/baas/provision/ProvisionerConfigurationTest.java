/*
 *
 *      Copyright (c) 2018-2025, lengleng All rights reserved.
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

package com.aiwork.baas.provision;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provisioner 数据源装配测试。
 *
 * @author ai-work
 * @date 2026/07/17
 */
class ProvisionerConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
        .withUserConfiguration(ProvisionerConfiguration.class)
        .withPropertyValues(
                "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/ai_work_baas",
                "spring.datasource.username=metadata_user",
                "spring.datasource.password=metadata_password",
                "baas.provisioner.url=jdbc:mysql://127.0.0.1:3306/mysql",
                "baas.provisioner.username=provisioner_user",
                "baas.provisioner.password=provisioner_password");

    @Test
    void bootMetadataDataSourceRemainsAutoConfiguredWhenProvisionerIsCreated() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ProjectProvisioner.class);
            assertThat(context).hasBean("dataSource");
            assertThat(context).doesNotHaveBean("provisionerDataSource");
            assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
        });
    }

}
