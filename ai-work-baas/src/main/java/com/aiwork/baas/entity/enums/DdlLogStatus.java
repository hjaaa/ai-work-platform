/*
 *
 *      Copyright (c) 2018-2026, lengleng All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 */

package com.aiwork.baas.entity.enums;

/**
 * DDL 日志状态(spec §9.2)。PENDING 仅用于预建 cleanup-drop。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public enum DdlLogStatus {

    /** 预建待调度(仅 cleanup-drop,owner_token 为 NULL)。 */
    PENDING,
    /** 执行中。 */
    RUNNING,
    /** 成功。 */
    SUCCESS,
    /** 失败。 */
    FAILED

}
