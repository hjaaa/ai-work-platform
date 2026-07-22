package com.aiwork.baas.data.meta;

import com.aiwork.baas.data.context.DataRole;
import com.aiwork.baas.data.error.DataApiException;
import com.aiwork.baas.entity.BaasTable;
import com.aiwork.baas.entity.BaasTableAcl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ACL × 角色 × representation 组合规则(spec §8.2)。
 *
 * @author ai-work
 * @date 2026/07/21
 */
class AclCheckerTest {

    private final AclChecker checker = new AclChecker();

    private static TableMeta metaWithAcl(boolean select, boolean insert, boolean update, boolean delete) {
        BaasTable table = new BaasTable();
        table.setTableName("t");
        BaasTableAcl anon = new BaasTableAcl();
        anon.setRole("anon");
        anon.setCanSelect(select);
        anon.setCanInsert(insert);
        anon.setCanUpdate(update);
        anon.setCanDelete(delete);
        return new TableMeta(table, List.of(), Map.of(), Map.of("anon", anon));
    }

    @Test
    void serviceRoleBypassesAcl() {
        assertThatCode(() -> checker.check(metaWithAcl(false, false, false, false), DataRole.SERVICE_ROLE,
                DataOperation.DELETE, false))
            .doesNotThrowAnyException();
    }

    @Test
    void deniedOperationThrows403() {
        assertThatThrownBy(
                () -> checker.check(metaWithAcl(true, false, true, true), DataRole.ANON, DataOperation.INSERT, false))
            .isInstanceOf(DataApiException.class)
            .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((DataApiException) e).status()).isEqualTo(403));
    }

    @Test
    void missingAclRowDeniesEverything() {
        BaasTable table = new BaasTable();
        table.setTableName("t");
        TableMeta meta = new TableMeta(table, List.of(), Map.of(), Map.of());

        assertThatThrownBy(() -> checker.check(meta, DataRole.ANON, DataOperation.SELECT, false))
            .isInstanceOf(DataApiException.class);
    }

    @Test
    void representationRequiresSelectInAdditionToWrite() {
        TableMeta writableNotReadable = metaWithAcl(false, true, true, true);

        assertThatThrownBy(() -> checker.check(writableNotReadable, DataRole.ANON, DataOperation.INSERT, true))
            .isInstanceOf(DataApiException.class)
            .hasMessageContaining("select");
        assertThatCode(() -> checker.check(writableNotReadable, DataRole.ANON, DataOperation.INSERT, false))
            .doesNotThrowAnyException();
        assertThatCode(() -> checker.check(metaWithAcl(true, true, true, true), DataRole.ANON, DataOperation.UPDATE,
                true))
            .doesNotThrowAnyException();
    }

}
