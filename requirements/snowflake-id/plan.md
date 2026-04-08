# 实施方案

## 步骤
1. 修改 `application.yml` 全局配置 `id-type: assign_id`
2. 修改 6 个实体类 `@TableId(type = IdType.ASSIGN_ID)`
3. 新增 Flyway V7 迁移脚本，去掉所有表的 AUTO_INCREMENT
4. 新增 Jackson 全局配置类，Long/long 序列化为 String
5. 编译验证

## 关键决策
- 全局 Jackson Long→String：方案 A，统一处理，避免遗漏
- 实体注解显式指定 type 而非仅依赖全局配置：更清晰，防止误解
