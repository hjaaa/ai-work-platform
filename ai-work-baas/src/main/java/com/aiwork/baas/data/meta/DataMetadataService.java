package com.aiwork.baas.data.meta;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aiwork.baas.data.error.DataApiException;
import com.aiwork.baas.entity.BaasColumn;
import com.aiwork.baas.entity.BaasTable;
import com.aiwork.baas.entity.BaasTableAcl;
import com.aiwork.baas.entity.enums.TableStatus;
import com.aiwork.baas.mapper.BaasColumnMapper;
import com.aiwork.baas.mapper.BaasTableAclMapper;
import com.aiwork.baas.mapper.BaasTableMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据面元数据直查(spec §7.5:每请求直查、MVP 不缓存)与表状态阻断映射(§9.5)。
 *
 * @author ai-work
 * @date 2026/07/21
 */
@Service
@RequiredArgsConstructor
public class DataMetadataService {

    private final BaasTableMapper tableMapper;

    private final BaasColumnMapper columnMapper;

    private final BaasTableAclMapper aclMapper;

    /**
     * 加载可供数据面访问的活动表元数据。
     *
     * @param projectId 项目 ID
     * @param tableName 表名
     * @return 表、列和 ACL 聚合元数据
     */
    public TableMeta loadActive(Long projectId, String tableName) {
        BaasTable table = tableMapper.selectOne(Wrappers.<BaasTable>lambdaQuery()
            .eq(BaasTable::getProjectId, projectId)
            .eq(BaasTable::getTableName, tableName));
        if (table == null || TableStatus.DELETED.name().equals(table.getStatus())) {
            throw DataApiException.notFound("表不存在");
        }
        if (!TableStatus.ACTIVE.name().equals(table.getStatus())) {
            throw DataApiException.forbidden("表暂不可用",
                    "表结构操作进行中或存在冲突,请稍后重试");
        }
        List<BaasColumn> columns = columnMapper.selectList(Wrappers.<BaasColumn>lambdaQuery()
            .eq(BaasColumn::getTableId, table.getId())
            .orderByAsc(BaasColumn::getId));
        Map<String, BaasColumn> columnsByName = new LinkedHashMap<>();
        columns.forEach(column -> columnsByName.put(column.getColumnName(), column));
        List<BaasTableAcl> acls = aclMapper
            .selectList(Wrappers.<BaasTableAcl>lambdaQuery().eq(BaasTableAcl::getTableId, table.getId()));
        Map<String, BaasTableAcl> aclByRole = new LinkedHashMap<>();
        acls.forEach(acl -> aclByRole.put(acl.getRole(), acl));
        return new TableMeta(table, columns, columnsByName, aclByRole);
    }

}
