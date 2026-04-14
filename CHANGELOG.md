# Changelog

所有版本的变更记录。格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，版本号遵循 [SemVer](https://semver.org/)。

---

## [1.0.0] - 2026-04-14

### Features
- AI 工作平台 Phase 1-5 全部完成（基础架构、项目管理、会话、文档解析）
- Phase 6-10 全量交付 — 接口拆分重构 + 会话线程 + Docker 部署 + 前端增强
- 日志基础设施 + 多环境配置
- 项目支持本地路径与项目类型（localPath / projectType）
- Skill 模块全栈实现（技能管理 CRUD）
- DevSpec 模块全栈实现（研发规范中心）
- 工作台前端深度增强 + Chat 服务优化

### Bug Fixes
- fix(spec): 错误消息中补充 codePath 关键词以符合测试断言

### Refactor
- 实体类引入 BaseEntity 统一公共字段

### Chore & Docs
- 完善上下文规则、经验沉淀与自动化 Hooks
- 更新需求文档与 AI 工作区进度记录
