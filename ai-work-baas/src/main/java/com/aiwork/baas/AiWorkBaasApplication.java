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

package com.aiwork.baas;

import com.aiwork.common.security.annotation.EnableAiWorkResourceServer;
import com.aiwork.common.swagger.annotation.EnableOpenApi;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * BaaS 平台服务:项目管理、动态数据 API、终端用户认证
 *
 * @author lengleng
 * @date 2026/07/17
 */
@EnableOpenApi("baas")
@EnableAiWorkResourceServer
@EnableDiscoveryClient
@EnableScheduling
@SpringBootApplication
public class AiWorkBaasApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiWorkBaasApplication.class, args);
    }

}
