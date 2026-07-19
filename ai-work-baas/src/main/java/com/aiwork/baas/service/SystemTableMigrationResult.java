/*
 *
 *      Copyright (c) 2018-2025, lengleng All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *  Redistributions of source code must retain the above copyright notice,
 *  this list of conditions and the following disclaimer.
 *
 */

package com.aiwork.baas.service;

/**
 * 系统表手动迁移的同步结果(spec v27 §7.3)。
 *
 * @author ai-work
 * @date 2026/07/19
 */
public record SystemTableMigrationResult(String status, boolean migrated) {
}
