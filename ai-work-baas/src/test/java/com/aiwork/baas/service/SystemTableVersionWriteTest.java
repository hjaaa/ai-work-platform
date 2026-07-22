/*
 *
 *      Copyright (c) 2018-2026, lengleng All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *  Redistributions of source code must retain the above copyright notice,
 *  this list of conditions and the following disclaimer.
 *
 */

package com.aiwork.baas.service;

import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.enums.ProjectStatus;
import com.aiwork.baas.mapper.BaasProjectMapper;
import com.aiwork.baas.provision.SystemTableManifest;
import com.aiwork.baas.support.PlanBContainerSupport;
import com.aiwork.baas.LifecycleTestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * system_table_version 三条写入路径与不写路径(spec §9.1/§14)。
 */
@SpringBootTest(classes = LifecycleTestApplication.class,
        properties = { "spring.config.import=", "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false" })
class SystemTableVersionWriteTest extends PlanBContainerSupport {

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", () -> MYSQL_USERNAME);
        registry.add("spring.datasource.password", () -> MYSQL_PASSWORD);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
        registry.add("baas.provisioner.url", () -> MYSQL.getJdbcUrl().replace("/ai_work_baas", "/mysql"));
        registry.add("baas.provisioner.username", () -> MYSQL_USERNAME);
        registry.add("baas.provisioner.password", () -> MYSQL_PASSWORD);
        registry.add("baas.project-db.host", MYSQL::getHost);
        registry.add("baas.project-db.port", () -> MYSQL.getMappedPort(3306));
        registry.add("server.servlet.context-path", () -> "");
    }

    @Autowired
    BaasProjectMapper projectMapper;

    @Autowired
    ProjectLifecycleService lifecycleService;

    @Autowired
    SystemTableMigrationService migrationService;

    /** 路径①:新项目开通置 ACTIVE 的同一事务写入当前版;返回对象与库行都须为当前版。 */
    @Test
    void provisioningWritesCurrentVersion() {
        var created = lifecycleService.createProject("verwrite-a", 1L);
        // 返回对象即带当前版:Studio 侧 fixture.project() 直接把该对象传给版本门禁,不可为 null
        assertThat(created.project().getSystemTableVersion()).isEqualTo(SystemTableManifest.CURRENT_VERSION);
        BaasProject project = projectMapper.selectById(created.project().getId());
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(project.getSystemTableVersion()).isEqualTo(SystemTableManifest.CURRENT_VERSION);
    }

    /** 路径②:v2 存量项目迁移成功回 ACTIVE 时写入;迁移前为 0。 */
    @Test
    void migrationSuccessWritesCurrentVersion() {
        var created = lifecycleService.createProject("verwrite-b", 1L);
        Long id = created.project().getId();
        // 人工降级为 v2 物理结构 + version 归零,模拟存量(root 连接跨库限定表名)
        new JdbcTemplate(mysqlDataSource()).execute(
                "ALTER TABLE `" + created.project().getDbName() + "`.`_users` DROP COLUMN deleted_at");
        projectMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.<BaasProject>lambdaUpdate()
            .eq(BaasProject::getId, id).set(BaasProject::getSystemTableVersion, 0));

        migrationService.scanOnce();

        BaasProject after = projectMapper.selectById(id);
        assertThat(after.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(after.getSystemTableVersion()).isEqualTo(SystemTableManifest.CURRENT_VERSION);
    }

    /** 路径③:物理已是 v3 而 version=0(如版本写入丢失)时,扫描按 MATCH_CURRENT 补写,不进 MIGRATING。 */
    @Test
    void scanBackfillsVersionWhenPhysicalAlreadyCurrent() {
        var created = lifecycleService.createProject("verwrite-c", 1L);
        Long id = created.project().getId();
        projectMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.<BaasProject>lambdaUpdate()
            .eq(BaasProject::getId, id).set(BaasProject::getSystemTableVersion, 0));

        migrationService.scanOnce();

        BaasProject after = projectMapper.selectById(id);
        assertThat(after.getSystemTableVersion()).isEqualTo(SystemTableManifest.CURRENT_VERSION);
        assertThat(after.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
    }

    /** 不写路径:迁移失败置 FAILED 时绝不写当前版。用 MISMATCH 结构触发 preflight 失败。 */
    @Test
    void failedMigrationNeverWritesVersion() {
        var created = lifecycleService.createProject("verwrite-d", 1L);
        Long id = created.project().getId();
        JdbcTemplate jdbc = new JdbcTemplate(mysqlDataSource());
        jdbc.execute("ALTER TABLE `" + created.project().getDbName() + "`.`_users` ADD COLUMN rogue int NULL");
        projectMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.<BaasProject>lambdaUpdate()
            .eq(BaasProject::getId, id).set(BaasProject::getSystemTableVersion, 0));

        migrationService.scanOnce();

        BaasProject after = projectMapper.selectById(id);
        assertThat(after.getStatus()).isEqualTo(ProjectStatus.FAILED);
        assertThat(after.getSystemTableVersion()).isEqualTo(0);
    }

}
