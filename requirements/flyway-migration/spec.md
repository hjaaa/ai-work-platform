# Feature Specification: 集成 Flyway 数据库版本管理

**Created**: 2026-04-07
**Status**: Draft

## User Story (P1) - 应用启动时自动执行数据库迁移

开发者部署应用时，Flyway 自动检测并执行未应用的迁移脚本，无需手动执行 SQL。

**Acceptance Scenarios**:

1. **Given** 全新数据库（无表），**When** 应用启动，**Then** Flyway 自动执行所有迁移脚本创建表结构
2. **Given** 已有数据的数据库（4 张表已存在），**When** 首次集成 Flyway 启动，**Then** Flyway 以 baseline 模式标记 V1 为已执行，不重复建表
3. **Given** 有新的迁移脚本 V2，**When** 应用启动，**Then** 仅执行 V2，V1 不重复执行
4. **Given** 迁移脚本有语法错误，**When** 应用启动，**Then** 启动失败并输出明确错误信息

## Requirements

### Functional Requirements

- **FR-001**: 集成 Flyway，应用启动时自动执行数据库迁移
- **FR-002**: 将现有 `init.sql`（4 张表）转为 Flyway 基线迁移脚本 `V1__init_schema.sql`
- **FR-003**: 已有数据的数据库首次启动时，使用 baseline 功能跳过 V1
- **FR-004**: 多环境（dev/test/prod）独立配置 Flyway 行为
- **FR-005**: 每个版本迁移脚本配套回滚脚本（手动执行，Flyway 社区版不支持自动 undo）

### Non-Functional Requirements

- **NFR-001**: 迁移脚本执行失败时，应用不启动（fail-fast）
- **NFR-002**: 回滚脚本与迁移脚本同目录，命名规范清晰

## Key Decisions

- **回滚策略**：Flyway 社区版不支持 `undo` 命令。采用"配套回滚脚本"方式：每个 `V{n}__xxx.sql` 配一个 `U{n}__rollback_xxx.sql`，需要回滚时手动执行
- **Baseline**：生产环境已有数据，首次启动设置 `baseline-on-migrate=true`，`baseline-version=1`

## Assumptions

- Flyway 版本由 Spring Boot 3.5.13 BOM 管理（Flyway 10.x）
- MySQL 8.x 兼容
- 迁移脚本放在 `platform-web/src/main/resources/db/migration/`（Flyway 默认路径）
