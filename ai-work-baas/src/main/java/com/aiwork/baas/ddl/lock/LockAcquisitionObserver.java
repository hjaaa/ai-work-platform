/*
 *
 *      Copyright (c) 2018-2026, lengleng All rights reserved.
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

package com.aiwork.baas.ddl.lock;

/**
 * Redis 已取得、尚未请求 GET_LOCK 时的确定性测试接缝；生产固定使用 NOOP。
 *
 * @author ai-work
 * @date 2026/07/18
 */
@FunctionalInterface
public interface LockAcquisitionObserver {

	LockAcquisitionObserver NOOP = handle -> {
	};

	void afterRedisBeforeAdvisory(LockHandle handle);

}
