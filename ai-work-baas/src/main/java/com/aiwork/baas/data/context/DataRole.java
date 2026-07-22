package com.aiwork.baas.data.context;

/**
 * 数据面请求身份三态(spec §7.4)。
 *
 * @author ai-work
 * @date 2026/07/21
 */
public enum DataRole {

    ANON("anon"),

    AUTHENTICATED("authenticated"),

    SERVICE_ROLE("service_role");

    private final String aclRole;

    DataRole(String aclRole) {
        this.aclRole = aclRole;
    }

    /**
     * baas_table_acl.role 取值;service_role 不受 ACL 约束。
     * @return acl 角色名,SERVICE_ROLE 返回 null
     */
    public String aclRole() {
        return this == SERVICE_ROLE ? null : aclRole;
    }

}
