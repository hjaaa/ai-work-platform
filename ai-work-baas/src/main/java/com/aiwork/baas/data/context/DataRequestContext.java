package com.aiwork.baas.data.context;

import com.aiwork.baas.entity.BaasProject;

/**
 * 鉴权结果上下文,经 request attribute 传递,不进 Spring SecurityContext(spec §7.5)。
 *
 * @param project 已鉴权项目
 * @param role 请求角色
 * @param endUserId 终端用户 ID(仅 AUTHENTICATED 非空)
 * @author ai-work
 * @date 2026/07/21
 */
public record DataRequestContext(BaasProject project, DataRole role, Long endUserId) {

    public static final String ATTRIBUTE = "com.aiwork.baas.data.context";

}
