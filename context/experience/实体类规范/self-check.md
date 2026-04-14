## 实体类规范 自检清单

- [ ] 所有 @TableName 实体类继承 BaseEntity（id、createdBy、createdAt、updatedAt、deleted）
- [ ] 实体类每个字段有 `/** 中文注释 */`，枚举字段列出可选值
- [ ] 新增实体类时同步创建 Flyway 迁移脚本，所有列带 `COMMENT '中文说明'`
- [ ] 新增表必须包含 BaseEntity 对应的 5 个公共列
- [ ] Java 字段注释与数据库 COMMENT 含义保持一致
- [ ] 子类使用 `@EqualsAndHashCode(callSuper = true)` 避免 Lombok equals/hashCode 警告
