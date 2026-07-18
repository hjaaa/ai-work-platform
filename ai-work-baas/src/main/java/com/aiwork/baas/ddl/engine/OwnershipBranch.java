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

/**
 * 日志所有权取得四分支(spec §9.2)。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public enum OwnershipBranch {

    /** 无记录:INSERT RUNNING + PREPARED。 */
    NEW_OPERATION,
    /** FAILED 重试:CAS 换 token + retry_count+1,保留原 step。 */
    RETRY_FAILED,
    /** 陈旧 RUNNING 接管:CAS 换 token,保留原 step。 */
    TAKE_OVER_RUNNING,
    /** PENDING cleanup 认领:CAS 置 RUNNING,step 保持 PREPARED。 */
    CLAIM_PENDING

}
