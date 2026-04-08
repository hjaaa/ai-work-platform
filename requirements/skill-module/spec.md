# 工作台技能和应用模块

## 背景
工作台侧边栏已有"技能和应用"按钮，但未实现功能。需要开发完整的 Skill 管理模块。

## 需求描述
- 平台用户可以创建、查看 Skill（技能）
- Skill 数据存储在数据库中（平台自建，非读取 CLI 配置）
- 点击侧边栏"技能和应用"后，主内容区替换欢迎页，展示 Skill 列表
- 每个 Skill 展示：名称 + 描述

## 数据模型
- skill 表：id, skill_id(业务主键), name, description, created_by, created_at, updated_at, deleted

## 功能范围
1. **后端**：Skill CRUD API（创建、列表查询）
2. **前端**：Skill 列表展示（替换欢迎页）、创建 Skill 入口
3. **数据库**：Flyway V6 迁移脚本建表

## 非功能需求
- 遵循现有分层规范（Controller/Service/Mapper）
- 前端样式遵循 DESIGN.md
