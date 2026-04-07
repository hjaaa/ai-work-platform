## 2026-04-07 MyBatis-Plus 3.5.x 分页插件缺少依赖

- **现象**：编译报错 `PaginationInnerInterceptor` 找不到符号，即使已引入 `mybatis-plus-spring-boot3-starter`
- **原因**：MyBatis-Plus 3.5.x 将分页插件的 JSqlParser 依赖拆分为独立模块，不再自动传递
- **正确做法**：使用分页功能时，必须额外引入 `mybatis-plus-jsqlparser` 依赖，版本与 starter 保持一致
- **适用场景**：任何使用 MyBatis-Plus 3.5.x + 分页的项目
