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

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/**
 * Provisioner 高权限账号数据源，仅管理面生命周期操作使用，
 * 与业务主数据源分离(spec §10.1)。
 *
 * @author ai-work
 * @date 2026/07/17
 */
@Configuration
public class ProvisionerConfiguration {

    private static final String MYSQL_DRIVER_CLASS_NAME = "com.mysql.cj.jdbc.Driver";

    @Bean("provisionerDataSource")
    public DataSource provisionerDataSource(@Value("${baas.provisioner.url}") String url,
            @Value("${baas.provisioner.username}") String username,
            @Value("${baas.provisioner.password}") String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, username, password);
        dataSource.setDriverClassName(MYSQL_DRIVER_CLASS_NAME);
        return dataSource;
    }

    @Bean
    public ProjectProvisioner projectProvisioner(@Qualifier("provisionerDataSource") DataSource provisionerDataSource) {
        return new ProjectProvisioner(provisionerDataSource);
    }

}
