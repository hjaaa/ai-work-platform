package com.aiwork.baas.controller.dto;

/**
 * 终端用户列表项(spec §7.3:含软删状态)。
 *
 * @param id 终端用户 ID
 * @param email 邮箱
 * @param createTime 创建时间(yyyy-MM-dd HH:mm:ss)
 * @param deletedAt 软删时间,未软删为 null
 * @author ai-work
 * @date 2026/07/22
 */
public record EndUserVO(Long id, String email, String createTime, String deletedAt) {
}
