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

package com.aiwork.baas.entity;

import com.aiwork.baas.entity.enums.ProjectStatus;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * BaaS 项目。
 *
 * @author ai-work
 * @date 2026/07/17
 */
@Data
@TableName("baas_project")
public class BaasProject {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String projectRef;

    private String name;

    private String dbName;

    private ProjectStatus status;

    private String provisionStep;

    private Long ownerUserId;

    private String allowedOrigins;

    private String runtimeDbUser;

    private String runtimeDbPasswordCipher;

    private Long ddlFenceEpoch;

    private LocalDateTime deleteAfter;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

}
