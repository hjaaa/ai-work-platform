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

package com.aiwork.baas.ddl.engine;

import com.aiwork.baas.exception.DdlConflictException;

/**
 * 守卫失效(spec §9.2):项目 epoch 已被推进或日志条件更新 0 行,本执行者已陈旧,事务整笔回滚。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public class StaleExecutorException extends DdlConflictException {

    public StaleExecutorException(String message) {
        super(message);
    }

}
