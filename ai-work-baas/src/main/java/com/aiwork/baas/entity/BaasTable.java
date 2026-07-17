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

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目表元数据。
 *
 * @author ai-work
 * @date 2026/07/17
 */
@Data
@TableName("baas_table")
public class BaasTable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private String tableName;

    @TableField("`comment`")
    private String comment;

    private String status;

    private String ownerColumn;

    private LocalDateTime deleteAfter;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

}
