# 需求：ID 生成策略改为雪花算法

## 背景
当前所有实体使用数据库自增 ID（`IdType.AUTO`），改为 MyBatis-Plus 内置雪花算法（`IdType.ASSIGN_ID`）。

## 变更范围
- 6 个实体类：Generation, Deployment, Project, SystemConfig, Conversation, ChatThread
- 全局配置 `id-type`
- 数据库表去掉 AUTO_INCREMENT
- 全局 Jackson 配置：Long 序列化为 String（防止前端精度丢失）

## 约束
- 数据库中无现有数据，无兼容性顾虑
- BIGINT 可容纳雪花 ID，无需改列类型
