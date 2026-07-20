/*
 *
 *      Copyright (c) 2018-2026, lengleng All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice,
 *  this list of conditions and the following disclaimer.
 *  Redistributions in binary form must reproduce the above copyright
 *  notice, this list of conditions and the following disclaimer in the
 *  documentation and/or other materials provided with the distribution.
 *  Neither the name of the pig4cloud.com developer nor the names of its
 *  contributors may be used to endorse or promote products derived from
 *  this software without specific prior written permission.
 *  Author: lengleng (wangiegie@gmail.com)
 *
 */

package com.aiwork.baas.service;

import com.aiwork.baas.controller.dto.ReconcileTriggerDTO;
import com.aiwork.baas.entity.BaasTable;
import com.aiwork.baas.mapper.BaasTableMapper;
import com.aiwork.baas.support.PlanBProjectIntegrationTestSupport;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        rootJdbc.execute("CREATE TABLE `" + db + "`.ra_cpk (id bigint NOT NULL AUTO_INCREMENT, "
                + "other bigint NOT NULL, PRIMARY KEY (id, other))" + baseline);
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
        rootJdbc.execute("CREATE TABLE `" + db + "`.ra_trigger (id bigint NOT NULL AUTO_INCREMENT, "
                + "v int, PRIMARY KEY (id))" + baseline);
        rootJdbc.execute("CREATE TRIGGER `" + db + "`.tr_ra_trigger BEFORE INSERT ON `" + db
                + "`.ra_trigger FOR EACH ROW SET NEW.v = COALESCE(NEW.v, 0)");
        rootJdbc.execute("CREATE TABLE `" + db + "`.ra_check (id bigint NOT NULL AUTO_INCREMENT, "
                + "v int, PRIMARY KEY (id), CONSTRAINT ck_ra CHECK (v >= 0))" + baseline);
        rootJdbc.execute("CREATE TABLE `" + db + "`.ra_bad_col (id bigint NOT NULL AUTO_INCREMENT, "
                + "`BadColumn` int, PRIMARY KEY (id))" + baseline);

        ObjectNode report = reconcileService.manualReconcile(project,
                new ReconcileTriggerDTO(UUID.randomUUID().toString()));

        Map<String, String> rejected = new LinkedHashMap<>();
        for (JsonNode item : report.withArray("rejectedImports")) {
            rejected.put(item.get("tableName").asText(), item.get("reason").asText());
        }
        Map<String, String> expected = Map.ofEntries(
                Map.entry("ra_dt6", "datetime 小数秒精度不可映射: t"),
                Map.entry("ra_tiny4", "tinyint 变体不可映射为 boolean: tinyint"),
                Map.entry("ra_gen", "生成列不可映射: g"),
                Map.entry("ra_fk", "存在外键"),
                Map.entry("ra_cpk", "主键不变量破坏(要求唯一主键 id bigint 自增)"),
                Map.entry("ra_inv", "索引不可映射为单列布尔模型: idx_n"),
                Map.entry("ra_desc", "索引不可映射为单列布尔模型: idx_n"),
                Map.entry("ra_ft", "索引不可映射为单列布尔模型: ft_v"),
                Map.entry("ra_func", "索引不可映射为单列布尔模型: idx_f"),
                Map.entry("ra_trigger", "存在表级触发器"),
                Map.entry("ra_check", "存在 CHECK 约束(含 NOT ENFORCED)"),
                Map.entry("ra_bad_col", "列标识符非法: BadColumn"),
                Map.entry("RA_UPPER", "表标识符非法: RA_UPPER"));
        assertThat(rejected).isEqualTo(expected);
        for (String name : expected.keySet()) {
            assertThat(tableMapper.selectCount(Wrappers.<BaasTable>lambdaQuery()
                    .eq(BaasTable::getProjectId, project.getId())
                    .eq(BaasTable::getTableName, name))).isZero();
        }
        assertThat(report.withArray("imported")).extracting(JsonNode::asText).containsExactly("ra_parent");
    }

}
