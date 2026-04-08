## 2026-04-07 Flyway 10.x 必须引入 flyway-mysql 模块

- **现象**：只引入 flyway-core 依赖，启动时报错无法识别 MySQL 方言
- **原因**：Flyway 10.x 将数据库方言支持拆分为独立模块，不再内置
- **正确做法**：同时引入 `flyway-core` 和 `flyway-mysql`，版本由 Spring Boot BOM 管理，不需要手动指定
- **适用场景**：Spring Boot 3.x + MySQL + Flyway 集成

## 2026-04-07 已有数据库首次集成 Flyway 必须配置 baseline

- **现象**：已有表和数据的数据库，首次启动 Flyway 时报错"Found non-empty schema without schema history table"
- **原因**：Flyway 检测到数据库非空但无 flyway_schema_history 表，拒绝执行
- **正确做法**：配置 `spring.flyway.baseline-on-migrate=true` + `spring.flyway.baseline-version=1`，Flyway 会自动创建历史表并标记基线版本为已执行
- **适用场景**：存量项目首次引入 Flyway

## 2026-04-07 Flyway 社区版不支持自动回滚

- **现象**：期望使用 `flyway undo` 命令自动回滚迁移
- **原因**：undo 功能仅 Flyway Teams/Enterprise 版支持，社区版无此能力
- **正确做法**：采用配套回滚脚本命名约定 `U{n}__rollback_xxx.sql`，与 `V{n}__xxx.sql` 一一对应，需要回滚时手动执行
- **适用场景**：使用 Flyway 社区版的项目

## 2026-04-07 YAML 中 spring.flyway 必须合并到已有 spring: 块内

- **现象**：Flyway 配置写成独立的 `spring:` 顶级块，导致覆盖已有的 `spring.datasource` 等配置
- **原因**：YAML 不允许同一层级有重复 key，后出现的会覆盖前面的
- **正确做法**：`spring.flyway` 必须写在已有的 `spring:` 块内，与 `spring.datasource` 同级缩进
- **适用场景**：application.yml 中添加任何 spring.* 子配置时
