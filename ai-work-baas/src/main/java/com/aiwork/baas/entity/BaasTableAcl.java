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
 * 表级访问控制。
 *
 * @author ai-work
 * @date 2026/07/17
 */
@Data
@TableName("baas_table_acl")
public class BaasTableAcl {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tableId;

    private String role;

    @TableField("is_can_select")
    private Boolean canSelect;

    @TableField("is_can_insert")
    private Boolean canInsert;

    @TableField("is_can_update")
    private Boolean canUpdate;

    @TableField("is_can_delete")
    private Boolean canDelete;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

}
