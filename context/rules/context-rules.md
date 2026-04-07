# 上下文加载规则

> Agent 在不同阶段应自动加载不同层次的上下文，避免预加载过多信息导致上下文腐蚀。

## 阶段→上下文映射

| 阶段 | 必须加载 | 按需加载 | 禁止加载 |
|------|----------|----------|----------|
| 需求分析 | `experience/index.md`, 匹配的经验文件 | `context/project/{相关服务}/` | 全量代码文件 |
| 方案设计 | `team/coding-standards.md`, `project/{服务}/architecture.md` | `team/api-compatibility.md`, 匹配的经验文件 | 无关服务的知识 |
| 编码实施 | `team/coding-standards.md`, `team/test-standards.md` | `team/logging-standards.md`, 经验中的 self-check | 需求文档全文（已在 plan 中提炼） |
| 代码审查 | `team/coding-standards.md`, `team/api-compatibility.md` | `team/logging-standards.md`, `team/test-standards.md` | 需求原始描述 |

## 加载原则

1. **先索引后内容**：先读 `experience/index.md` 确定需要加载哪些文件
2. **宁缺毋滥**：不确定是否需要时，不加载。Agent 可以在需要时主动检索
3. **上下文预算**：单次加载的 context 文件总量不超过 5000 tokens
