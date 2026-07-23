package com.aiwork.baas.data.context;

import com.aiwork.baas.entity.BaasProject;

/**
 * 鉴权结果上下文,经 request attribute 传递,不进 Spring SecurityContext(spec §7.5)。
 *
 * @param project 已鉴权项目
 * @param role 请求角色
 * @param endUserId 终端用户 ID(仅 AUTHENTICATED 非空)
 * @param sessionId 终端用户会话 ID(仅 AUTHENTICATED 非空,spec §7.6:logout 撤销当前会话依据)
 * @author ai-work
 * @date 2026/07/21
 */
public record DataRequestContext(BaasProject project, DataRole role, Long endUserId, Long sessionId) {

    public static final String ATTRIBUTE = "com.aiwork.baas.data.context";

}
