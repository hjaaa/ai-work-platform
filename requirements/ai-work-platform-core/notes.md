# 开发笔记

## 技术发现
- AI 引擎使用服务器已部署的 Claude Code CLI，非 API 调用方式
- 需要研究 Claude Code CLI 的批处理/自动化调用方式

## 踩坑记录

### 2026-04-07 方案设计未对齐 Constitution 技术栈版本
- 现象：plan.md 中写了 Java 17+ / Spring Boot 3.x，但 constitution.md 明确要求 Java 21+ / Spring Boot 3.5.x
- 原因：方案设计时未先读取 constitution.md，依赖了通用常识而非项目约束
- 修正：方案设计前必须先加载 constitution.md 检查技术栈约束
