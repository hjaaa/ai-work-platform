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

import javax.sql.DataSource;

/**
 * Provisioner 高权限数据源 holder：刻意不把 DataSource 暴露为 Spring Bean，
 * 避免平台主数据源自动装配退避(DataSourceAutoConfiguration 按类型条件退避)。
 *
 * @author ai-work
 * @date 2026/07/18
 */
public final class ProvisionerDataSourceHolder {

    private final DataSource dataSource;

    public ProvisionerDataSourceHolder(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public DataSource dataSource() {
        return dataSource;
    }

}
