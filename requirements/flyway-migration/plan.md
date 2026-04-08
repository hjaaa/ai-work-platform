# Implementation Plan: 集成 Flyway 数据库版本管理

**Date**: 2026-04-07 | **Spec**: requirements/flyway-migration/spec.md

## Summary

在 Spring Boot 3.5.13 项目中集成 Flyway，将现有手动 `init.sql` 转为版本化迁移脚本，支持多环境配置和配套回滚脚本。

## Technical Context

- **Spring Boot**: 3.5.13（BOM 管理 Flyway 10.x，含 `flyway-mysql` 适配）
- **Database**: MySQL 8.x
- **ORM**: MyBatis-Plus 3.5.15（与 Flyway 无冲突，Flyway 先执行迁移，MP 后初始化）
- **模块**: 变更集中在 `platform-web`（数据源和启动入口所在模块）

## Constitution Check

| 原则 | 是否合规 | 说明 |
|------|---------|------|
| VI. 简单性优先 | ✅ | Flyway 是 Spring Boot 原生支持的迁移工具，无额外抽象 |
| IV. 兼容性优先 | ✅ | 使用 baseline 模式，不影响已有数据 |
| 新增依赖评估 | ✅ | 见下方评估 |

### 新增依赖评估

| 项目 | 说明 |
|------|------|
| 引入原因 | 数据库 schema 版本化管理，替代手动 init.sql |
| 替代方案 | Liquibase（XML 格式、更重）、手动 SQL（当前方式，无版本追踪） |
| 选择理由 | Flyway 纯 SQL 脚本、Spring Boot 原生集成、学习成本低 |
| 体积 | flyway-core ~300KB + flyway-mysql ~20KB |
| License | Apache 2.0（社区版） |
| 维护 | Redgate 维护，活跃度高 |

## Implementation Steps

### Step 1: 添加 Flyway 依赖

在 `platform-web/pom.xml` 添加：
- `org.flywaydb:flyway-core`（Spring Boot BOM 管理版本）
- `org.flywaydb:flyway-mysql`（MySQL 方言支持，Flyway 10.x 必需）

### Step 2: 创建迁移脚本

将 `db/init.sql` 内容转为 Flyway 迁移脚本：

```
platform-web/src/main/resources/db/migration/
├── V1__init_schema.sql          # 基线：4 张表（project/conversation/generation/deployment）
└── U1__rollback_init_schema.sql # 配套回滚：DROP 4 张表
```

**注意**：
- V1 脚本去掉 `CREATE DATABASE` 和 `USE` 语句（Flyway 使用 datasource 指定的库）
- V1 脚本保留 `CREATE TABLE IF NOT EXISTS`（安全性）
- U1 回滚脚本是手动执行的文档，Flyway 社区版不会自动执行

### Step 3: 多环境 Flyway 配置

**application.yml**（基础配置）：
- `spring.flyway.locations=classpath:db/migration`
- `spring.flyway.baseline-on-migrate=true`（已有数据库首次启动时自动 baseline）
- `spring.flyway.baseline-version=1`（标记 V1 为已执行）

**application-dev.yml**：
- `spring.flyway.enabled=true`

**application-test.yml**：
- `spring.flyway.enabled=true`
- `spring.flyway.clean-disabled=true`（禁止 clean 操作防误删）

**application-prod.yml**：
- `spring.flyway.enabled=true`
- `spring.flyway.clean-disabled=true`
- `spring.flyway.out-of-order=false`（严格顺序）

### Step 4: 验证

- dev 环境启动验证：Flyway 建表 `flyway_schema_history` 并标记 V1 为 baseline
- 确认 MyBatis-Plus 正常工作（Flyway 先于 MP 初始化）

## Risk & Rollback

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| 已有数据库首次启动重复建表 | 低 | 高 | baseline-on-migrate + CREATE TABLE IF NOT EXISTS |
| Flyway 10.x 与 MySQL 版本不兼容 | 低 | 高 | flyway-mysql 适配模块 |
| 回滚需要手动执行 | 确定 | 中 | U{n} 脚本有完整回滚 SQL，文档化流程 |

**回滚方案**：移除 Flyway 依赖和配置即可回退到手动模式，`flyway_schema_history` 表不影响业务。
