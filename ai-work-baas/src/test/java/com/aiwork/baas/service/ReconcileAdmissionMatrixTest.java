package com.aiwork.baas.service;

import com.aiwork.baas.controller.dto.ReconcileTriggerDTO;
import com.aiwork.baas.entity.BaasTable;
import com.aiwork.baas.mapper.BaasTableMapper;
import com.aiwork.baas.support.PlanBProjectIntegrationTestSupport;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReconcileAdmissionMatrixTest extends PlanBProjectIntegrationTestSupport {

    @Autowired
    private ReconcileService reconcileService;

    @Autowired
    private BaasTableMapper tableMapper;

    @Override
    protected String projectNamePrefix() {
        return "radm";
    }

    @Test
    void remainingInadmissibleStructuresRejected() {
        String db = project.getDbName();
        String baseline = " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC";
        rootJdbc.execute("CREATE TABLE `" + db + "`.ra_dt6 (id bigint NOT NULL AUTO_INCREMENT, "
                + "t datetime(6), PRIMARY KEY (id))" + baseline);
        rootJdbc.execute("CREATE TABLE `" + db + "`.ra_tiny4 (id bigint NOT NULL AUTO_INCREMENT, "
                + "f tinyint, PRIMARY KEY (id))" + baseline);
        rootJdbc.execute("CREATE TABLE `" + db + "`.ra_gen (id bigint NOT NULL AUTO_INCREMENT, "
                + "a int, g int GENERATED ALWAYS AS (a + 1) VIRTUAL, PRIMARY KEY (id))" + baseline);
        rootJdbc.execute("CREATE TABLE `" + db + "`.ra_parent (id bigint NOT NULL AUTO_INCREMENT, "
                + "PRIMARY KEY (id))" + baseline);
        rootJdbc.execute("CREATE TABLE `" + db + "`.ra_fk (id bigint NOT NULL AUTO_INCREMENT, p bigint, "
                + "PRIMARY KEY (id), KEY idx_p (p), CONSTRAINT fk_ra FOREIGN KEY (p) REFERENCES `" + db
                + "`.ra_parent (id))" + baseline);
        rootJdbc.execute("CREATE TABLE `" + db + "`.ra_cpk (a bigint NOT NULL, b bigint NOT NULL, "
                + "PRIMARY KEY (a, b))" + baseline);
        rootJdbc.execute("CREATE TABLE `" + db + "`.ra_inv (id bigint NOT NULL AUTO_INCREMENT, n int, "
                + "PRIMARY KEY (id), KEY idx_n (n) INVISIBLE)" + baseline);
        rootJdbc.execute("CREATE TABLE `" + db + "`.ra_desc (id bigint NOT NULL AUTO_INCREMENT, n int, "
                + "PRIMARY KEY (id), KEY idx_n (n DESC))" + baseline);
        rootJdbc.execute("CREATE TABLE `" + db + "`.ra_ft (id bigint NOT NULL AUTO_INCREMENT, "
                + "v varchar(200), PRIMARY KEY (id), FULLTEXT KEY ft_v (v))" + baseline);
        rootJdbc.execute("CREATE TABLE `" + db + "`.ra_func (id bigint NOT NULL AUTO_INCREMENT, "
                + "v varchar(200), PRIMARY KEY (id), KEY idx_f ((lower(v))))" + baseline);
        rootJdbc.execute("CREATE TABLE `" + db + "`.`RA_UPPER` (id bigint NOT NULL AUTO_INCREMENT, "
                + "PRIMARY KEY (id))" + baseline);

        ObjectNode report = reconcileService.manualReconcile(project,
                new ReconcileTriggerDTO(UUID.randomUUID().toString()));

        String rejected = report.get("rejectedImports").toString();
        for (String name : List.of("ra_dt6", "ra_tiny4", "ra_gen", "ra_fk", "ra_cpk", "ra_inv", "ra_desc",
                "ra_ft", "ra_func", "RA_UPPER")) {
            assertThat(rejected).contains(name);
            assertThat(tableMapper.selectCount(Wrappers.<BaasTable>lambdaQuery()
                    .eq(BaasTable::getProjectId, project.getId())
                    .eq(BaasTable::getTableName, name))).isZero();
        }
        assertThat(report.get("imported").toString()).contains("ra_parent");
    }

}
