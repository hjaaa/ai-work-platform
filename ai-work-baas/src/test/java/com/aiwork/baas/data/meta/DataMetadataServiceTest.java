package com.aiwork.baas.data.meta;

import com.aiwork.baas.data.error.DataApiException;
import com.aiwork.baas.entity.BaasColumn;
import com.aiwork.baas.entity.BaasTable;
import com.aiwork.baas.entity.BaasTableAcl;
import com.aiwork.baas.entity.enums.TableStatus;
import com.aiwork.baas.mapper.BaasColumnMapper;
import com.aiwork.baas.mapper.BaasTableAclMapper;
import com.aiwork.baas.mapper.BaasTableMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 数据面元数据直查与表状态阻断测试。
 *
 * @author ai-work
 * @date 2026/07/21
 */
class DataMetadataServiceTest {

    private BaasTableMapper tableMapper;

    private BaasColumnMapper columnMapper;

    private BaasTableAclMapper aclMapper;

    private DataMetadataService service;

    @BeforeEach
    void setUp() {
        initTableInfo(BaasTable.class);
        initTableInfo(BaasColumn.class);
        initTableInfo(BaasTableAcl.class);
        tableMapper = Mockito.mock(BaasTableMapper.class);
        columnMapper = Mockito.mock(BaasColumnMapper.class);
        aclMapper = Mockito.mock(BaasTableAclMapper.class);
        service = new DataMetadataService(tableMapper, columnMapper, aclMapper);
    }

    @Test
    void missingTableThrows404WithoutLoadingChildren() {
        when(tableMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.loadActive(7L, "orders"))
            .isInstanceOf(DataApiException.class)
            .satisfies(error -> assertThat(((DataApiException)error).status()).isEqualTo(404))
            .hasMessage("表不存在");
        verifyNoInteractions(columnMapper, aclMapper);
    }

    @Test
    void deletedTableThrows404WithoutLoadingChildren() {
        when(tableMapper.selectOne(any())).thenReturn(table(21L, TableStatus.DELETED));

        assertThatThrownBy(() -> service.loadActive(7L, "orders"))
            .isInstanceOf(DataApiException.class)
            .satisfies(error -> assertThat(((DataApiException)error).status()).isEqualTo(404))
            .hasMessage("表不存在");
        verifyNoInteractions(columnMapper, aclMapper);
    }

    @ParameterizedTest
    @EnumSource(value = TableStatus.class, names = { "CREATING", "ALTERING", "FAILED", "CONFLICT" })
    void unavailableTableStatusThrows403WithHint(TableStatus status) {
        when(tableMapper.selectOne(any())).thenReturn(table(21L, status));

        assertThatThrownBy(() -> service.loadActive(7L, "orders"))
            .isInstanceOf(DataApiException.class)
            .satisfies(error -> {
                DataApiException dataError = (DataApiException)error;
                assertThat(dataError.status()).isEqualTo(403);
                assertThat(dataError.hint()).isEqualTo("表结构操作进行中或存在冲突,请稍后重试");
            });
        verifyNoInteractions(columnMapper, aclMapper);
    }

    @Test
    void activeTableLoadsByProjectAndNameAndAggregatesChildrenByCurrentTableId() {
        BaasTable table = table(21L, TableStatus.ACTIVE);
        BaasColumn id = column(101L, 21L, "id");
        BaasColumn title = column(102L, 21L, "title");
        BaasTableAcl anon = acl(201L, 21L, "anon");
        when(tableMapper.selectOne(any())).thenReturn(table);
        when(columnMapper.selectList(any())).thenReturn(List.of(id, title));
        when(aclMapper.selectList(any())).thenReturn(List.of(anon));

        TableMeta meta = service.loadActive(7L, "orders");

        assertThat(meta.table()).isSameAs(table);
        assertThat(meta.columns()).containsExactly(id, title);
        assertThat(meta.columnsByName()).containsOnlyKeys("id", "title")
            .containsEntry("id", id)
            .containsEntry("title", title);
        assertThat(meta.aclByRole()).containsOnlyKeys("anon").containsEntry("anon", anon);
        assertTableQuery();
        assertChildQueries(21L);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void assertTableQuery() {
        ArgumentCaptor<LambdaQueryWrapper> wrapperCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        Mockito.verify(tableMapper).selectOne(wrapperCaptor.capture());
        LambdaQueryWrapper<?> wrapper = wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("project_id", "table_name");
        assertThat(wrapper.getParamNameValuePairs().values()).containsExactlyInAnyOrder(7L, "orders");
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void assertChildQueries(Long tableId) {
        ArgumentCaptor<LambdaQueryWrapper> columnWrapperCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        Mockito.verify(columnMapper).selectList(columnWrapperCaptor.capture());
        LambdaQueryWrapper<?> columnWrapper = columnWrapperCaptor.getValue();
        assertThat(columnWrapper.getSqlSegment()).contains("table_id", "ORDER BY id ASC");
        assertThat(columnWrapper.getParamNameValuePairs()).containsValue(tableId);

        ArgumentCaptor<LambdaQueryWrapper> aclWrapperCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        Mockito.verify(aclMapper).selectList(aclWrapperCaptor.capture());
        LambdaQueryWrapper<?> aclWrapper = aclWrapperCaptor.getValue();
        assertThat(aclWrapper.getSqlSegment()).contains("table_id");
        assertThat(aclWrapper.getParamNameValuePairs()).containsValue(tableId);
    }

    private static void initTableInfo(Class<?> entityType) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityType);
    }

    private static BaasTable table(Long id, TableStatus status) {
        BaasTable table = new BaasTable();
        table.setId(id);
        table.setProjectId(7L);
        table.setTableName("orders");
        table.setStatus(status.name());
        return table;
    }

    private static BaasColumn column(Long id, Long tableId, String name) {
        BaasColumn column = new BaasColumn();
        column.setId(id);
        column.setTableId(tableId);
        column.setColumnName(name);
        return column;
    }

    private static BaasTableAcl acl(Long id, Long tableId, String role) {
        BaasTableAcl acl = new BaasTableAcl();
        acl.setId(id);
        acl.setTableId(tableId);
        acl.setRole(role);
        return acl;
    }

}
