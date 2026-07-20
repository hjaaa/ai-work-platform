/*
 *
 *      Copyright (c) 2018-2026, lengleng All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 * Neither the name of the pig4cloud.com developer nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 * Author: lengleng (wangiegie@gmail.com)
 *
 */

package com.aiwork.baas.provision;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.function.LongSupplier;

/**
 * 物理前置条件 fail-closed 校验(spec §9.1)：innodb_page_size 必须为 16384，
 * 否则禁止建项目与一切 Plan B DDL 操作。懒检查 + 缓存，查询失败同样视为不满足。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public class PhysicalPreconditions {

    public static final long REQUIRED_PAGE_SIZE = 16384L;

    private final LongSupplier pageSizeQuery;

    private volatile String failureReason;

    private volatile boolean checked;

    public PhysicalPreconditions(LongSupplier pageSizeQuery) {
        this.pageSizeQuery = pageSizeQuery;
    }

    public static PhysicalPreconditions fromDataSource(DataSource provisionerDataSource) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(provisionerDataSource);
        return new PhysicalPreconditions(() -> {
            Long pageSize = jdbcTemplate.queryForObject("SELECT @@innodb_page_size", Long.class);
            return pageSize == null ? -1L : pageSize;
        });
    }

    public synchronized void refresh() {
        try {
            long pageSize = pageSizeQuery.getAsLong();
            failureReason = pageSize == REQUIRED_PAGE_SIZE ? null
                    : "innodb_page_size=" + pageSize + "，要求 16384，禁止建项目与 DDL 操作(spec §9.1)";
        }
        catch (Exception exception) {
            failureReason = "innodb_page_size 查询失败，fail-closed(BAAS_PHYSICAL_PRECONDITIONS_FAILED)";
        }
        checked = true;
    }

    public void assertSatisfied() {
        if (!checked) {
            refresh();
        }
        if (failureReason != null) {
            throw new IllegalStateException(failureReason);
        }
    }

    public boolean isSatisfied() {
        if (!checked) {
            refresh();
        }
        return failureReason == null;
    }

}
