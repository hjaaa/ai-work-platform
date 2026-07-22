package com.aiwork.baas.data.meta;

import com.aiwork.baas.entity.BaasColumn;
import com.aiwork.baas.entity.BaasTable;
import com.aiwork.baas.entity.BaasTableAcl;

import java.util.List;
import java.util.Map;

/**
 * 数据面单表元数据聚合(每请求直查结果,spec §7.5)。
 *
 * @author ai-work
 * @date 2026/07/21
 */
public record TableMeta(BaasTable table, List<BaasColumn> columns, Map<String, BaasColumn> columnsByName,
        Map<String, BaasTableAcl> aclByRole) {

    /**
     * owner 列元数据。
     *
     * @return 未配置 owner 时返回 null
     */
    public BaasColumn ownerColumn() {
        String ownerColumnName = table.getOwnerColumn();
        return ownerColumnName == null ? null : columnsByName.get(ownerColumnName);
    }

}
