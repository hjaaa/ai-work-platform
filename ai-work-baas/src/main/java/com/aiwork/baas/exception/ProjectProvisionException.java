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

package com.aiwork.baas.exception;

/**
 * 项目开通(provisioning)过程中的基础设施失败,区别于客户端可修复的状态冲突,应映射为 5xx。
 *
 * @author ai-work
 * @date 2026/07/17
 */
public class ProjectProvisionException extends RuntimeException {

    public ProjectProvisionException(String message, Throwable cause) {
        super(message, cause);
    }

}
