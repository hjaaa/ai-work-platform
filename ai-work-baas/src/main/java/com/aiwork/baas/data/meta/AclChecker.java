package com.aiwork.baas.data.meta;

import com.aiwork.baas.data.context.DataRole;
import com.aiwork.baas.data.error.DataApiException;
import com.aiwork.baas.entity.BaasTableAcl;
import org.springframework.stereotype.Component;

/**
 * 表级 ACL 检查(spec §8.2):四权限独立;唯一组合规则为 representation 额外要求 select,
 * 检查前置于写入。
 *
 * @author ai-work
 * @date 2026/07/21
 */
@Component
public class AclChecker {

    /**
     * 检查当前角色是否允许执行指定操作。
     *
     * @param meta 表元数据
     * @param role 数据面角色
     * @param operation 数据操作
     * @param representation 写入后是否需要返回数据
     */
    public void check(TableMeta meta, DataRole role, DataOperation operation, boolean representation) {
        if (role == DataRole.SERVICE_ROLE) {
            return;
        }
        BaasTableAcl acl = meta.aclByRole().get(role.aclRole());
        if (!allowed(acl, operation)) {
            throw DataApiException.forbidden("当前角色无 " + operation.aclLabel() + " 权限", null);
        }
        if (representation && !allowed(acl, DataOperation.SELECT)) {
            throw DataApiException.forbidden("return=representation 需要 select 权限",
                    "representation 是一次读取,请同时开启 select 或去掉 Prefer 头");
        }
    }

    private static boolean allowed(BaasTableAcl acl, DataOperation operation) {
        if (acl == null) {
            return false;
        }
        return switch (operation) {
            case SELECT -> Boolean.TRUE.equals(acl.getCanSelect());
            case INSERT -> Boolean.TRUE.equals(acl.getCanInsert());
            case UPDATE -> Boolean.TRUE.equals(acl.getCanUpdate());
            case DELETE -> Boolean.TRUE.equals(acl.getCanDelete());
        };
    }

}
