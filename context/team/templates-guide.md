# 文档模板参考指南

> 本项目保留了 `.specify/templates/` 下的模板文件作为**参考资源**，不作为强制流程。
> Agent 在需要生成需求文档、方案文档或任务清单时，可参考这些模板的结构，但不必严格遵循。

## 可用模板

| 模板 | 路径 | 参考场景 |
|------|------|----------|
| 需求规格模板 | `.specify/templates/spec-template.md` | 需要写结构化需求文档时参考其章节结构 |
| 实施计划模板 | `.specify/templates/plan-template.md` | 需要写技术方案时参考其技术上下文和阶段划分 |
| 任务分解模板 | `.specify/templates/tasks-template.md` | 需要拆解任务时参考其 checklist 格式和依赖标记 |
| 检查清单模板 | `.specify/templates/checklist-template.md` | 需要写质量检查清单时参考其格式 |

## 使用原则

1. **参考而非遵循**：模板提供结构建议，实际输出根据需求复杂度灵活调整
2. **简单需求不需要模板**：一段话能说清楚的需求，不必套模板格式
3. **复杂需求可借鉴**：跨服务、多阶段的需求，可参考 spec-template 的章节结构确保不遗漏

## 权威来源

- 项目原则的权威来源是 `.specify/memory/constitution.md`
- `context/team/*.md` 是从 constitution 提炼的执行级规范
- 两者冲突时以 constitution 为准
