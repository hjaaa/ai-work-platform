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

package com.aiwork.baas.datasource;

import com.alibaba.druid.pool.DruidDataSource;
import com.aiwork.baas.security.crypto.BaasCryptoService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 项目连接池注册表生产装配。
 *
 * @author ai-work
 * @date 2026/07/17
 */
@Configuration
public class RegistryConfiguration {

    @Bean
    public ProjectDataSourceRegistry projectDataSourceRegistry(BaasCryptoService cryptoService,
            @Value("${baas.project-db.host:ai-work-mysql}") String host,
            @Value("${baas.project-db.port:3306}") int port,
            @Value("${baas.registry.max-pools:50}") int maxPools,
            @Value("${baas.registry.per-pool-max:10}") int perPoolMax,
            @Value("${baas.registry.global-max-connections:200}") int globalMaxConnections) {
        return new ProjectDataSourceRegistry(project -> {
            DruidDataSource dataSource = new DruidDataSource();
            dataSource.setUrl("jdbc:mysql://" + host + ":" + port + "/" + project.getDbName()
                    + "?characterEncoding=utf8&useSSL=false&serverTimezone=GMT%2B8");
            dataSource.setUsername(project.getRuntimeDbUser());
            dataSource.setPassword(cryptoService.decrypt(project.getRuntimeDbPasswordCipher(),
                    project.getId() + ":db_password:" + project.getId()));
            dataSource.setMaxActive(perPoolMax);
            return dataSource;
        }, maxPools, perPoolMax, globalMaxConnections);
    }

}
