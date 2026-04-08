## 2026-04-07 MyBatis-Plus 3.5.x 分页插件缺少依赖

- **现象**：编译报错 `PaginationInnerInterceptor` 找不到符号，即使已引入 `mybatis-plus-spring-boot3-starter`
- **原因**：MyBatis-Plus 3.5.x 将分页插件的 JSqlParser 依赖拆分为独立模块，不再自动传递
- **正确做法**：使用分页功能时，必须额外引入 `mybatis-plus-jsqlparser` 依赖，版本与 starter 保持一致
- **适用场景**：任何使用 MyBatis-Plus 3.5.x + 分页的项目

## 2026-04-08 雪花算法迁移必须同步四个地方

- **现象**：将 ID 策略从 AUTO 改为 ASSIGN_ID（雪花算法）时，容易遗漏某个环节导致 ID 生成异常或前端显示错误
- **原因**：雪花 ID 是 64 位 Long，涉及应用层和数据库层的多处配置联动
- **正确做法**：切换雪花算法时必须同步修改四个地方——
  1. `application.yml` 全局配置 `id-type: assign_id`
  2. 所有实体类 `@TableId(type = IdType.ASSIGN_ID)`
  3. Flyway 迁移脚本去掉所有表的 `AUTO_INCREMENT`（`ALTER TABLE xxx MODIFY id BIGINT NOT NULL`）
  4. Jackson 全局配置 Long→String 序列化（`Jackson2ObjectMapperBuilderCustomizer`），防止前端 JS 精度丢失
- **适用场景**：任何 MyBatis-Plus 项目从自增 ID 切换到雪花算法时
