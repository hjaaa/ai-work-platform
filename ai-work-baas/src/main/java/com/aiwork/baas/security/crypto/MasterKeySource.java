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

package com.aiwork.baas.security.crypto;

import java.util.Map;

/**
 * 主密钥来源抽象:生产只允许系统环境变量实现;测试可注入固定密钥实现(spec §12.1)
 *
 * @author ai-work
 * @date 2026/07/17
 */
public interface MasterKeySource {

    Map<String, String> masterKeys();

    String activeKeyId();

}
