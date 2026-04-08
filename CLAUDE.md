# AI Work Platform - 项目级指令

## 核心原则

1. **上下文完整性决定产出质量**：给 AI 完整信息比约束 AI 流程更重要
2. **每次工作都让下次更容易**：知识必须沉淀，边际成本必须递减
3. **先跑起来，让问题驱动演进**：不预设完美架构，不强制线性流程

## 两个入口

本项目只有两个核心命令，复杂性藏在 Agent 的智能路由里：

| 命令 | 用途 | 何时用 |
|------|------|--------|
| `/req-dev` | 需求开发（从意图到代码） | 开始新需求、继续已有需求、变更需求 |
| `/optimize-flow` | 经验沉淀（从踩坑到复利） | AI 犯错后记录、需求完成后总结、随时记录发现 |

两者构成闭环：`/req-dev` 从 experience/ 读取经验 → `/optimize-flow` 向 experience/ 写入经验。

## 前端设计规范（强制）

修改任何 `*.vue` 文件的样式前，**必须先读取 `platform-frontend/DESIGN.md`**。该文件定义了从 Teambition 提取的完整设计系统（颜色、字体、间距、圆角、阴影等），所有前端样式必须严格遵守。违规由 lint-rules L009~L013 自动检查。

## 上下文自动加载（每次会话必执行）

每次新会话开始时，**在执行任何任务之前**：

1. 读取 `context/experience/index.md`，根据用户意图中的关键词匹配相关经验文件
2. 读取 `context/rules/risk-rules.md`，扫描用户需求中的高风险关键词
3. 如果匹配到经验条目，按需加载对应的经验文件（不要全量加载）
4. 如果是恢复会话（用户说"继续"/"恢复"），读取 `requirements/{id}/process.txt` 和 `meta.yaml`

**禁止**：一次性加载 context/ 下所有文件。始终遵循 JIT（即时）加载原则。

## 知识沉淀规则

### 自动触发时机

1. **AI 犯错被纠正时**：立即记录到当前需求的 `notes.md`
2. **发现新技术约束时**：记录到 `notes.md`
3. **需求完成时**：主动询问是否执行 `/optimize-flow`

### 记录格式（2 分钟原则）

```markdown
### [日期] [简述]
- 现象：AI 在做 X 时犯了 Y 错误
- 原因：AI 不知道 Z
- 修正：[具体的正确做法]
```

### "走不通的路"必须记录

当尝试某个方案失败时，**立即**记录到 `process.txt` 的 `What Did NOT Work` 区域：

```
- 尝试 [方案] → 失败，因为 [原因]（不要再试这条路）
```

这是最重要的记录——防止恢复会话后重蹈覆辙。

### 经验置信度

沉淀到 `experience/` 的经验带有置信度标记：

| 级别 | 含义 | 升降条件 |
|------|------|----------|
| 低 | 首次记录，未验证 | 新沉淀默认为低 |
| 中 | 第二次遇到并确认有效 | 低→中 |
| 高 | 第三次验证，或涉及资金/安全 | 中→高，可升级为正式规范 |

被证伪的经验必须降级或删除。

## 自动化 Hooks

以下行为通过 `.claude/settings.json` 中的 hook 自动执行：

| Hook | 触发时机 | 做什么 |
|------|----------|--------|
| PreCompact | 上下文压缩前 | 自动将当前进度写入 `process.txt`，防止压缩丢失信息 |
| Stop | 每次响应结束后 | 检查需求是否已完成但 notes 未沉淀，提醒执行 `/optimize-flow` |
| Stop | 每次响应结束后 | 批量 lint 检查最近修改的 Java/XML 文件，输出违规+修复指令 |

## Lint 自动检查

`context/rules/lint-rules.md` 中定义了自动检查规则，由 Stop hook 批量执行。

**设计原则**（来自 OpenAI Harness Engineering）：
- 规则不仅检查违规，还在**错误信息中注入修复指令**，Agent 可直接执行修复
- 不逐次检查（每次 Edit 后），而是**攒到 Stop 时批量处理**，减少阻塞
- 规则从三个渠道生长：Constitution 原则 / experience 高置信度条目 / 反复出现的审查问题

**规则升级路径**：
```
experience/（经验层，人工提醒）
    ↓ 置信度升为"高"
context/rules/lint-rules.md（规则层，机械检查）
    ↓ 规则成熟后
ArchUnit / Checkstyle（工具层，编译期强制）
```

## 风险自动提醒

参照 `context/rules/risk-rules.md` 中的关键词表，命中时**主动提醒**，不等用户问。

## 目录约定

| 目录 | 用途 |
|------|------|
| `context/team/` | 团队级规范（编码、日志、测试、API 兼容） |
| `context/project/` | 项目/服务级知识（架构、领域模型） |
| `context/experience/` | 踩坑经验（通过 index.md 按需加载） |
| `context/rules/` | Agent 自动化约束（上下文映射、风险识别、代码模式） |
| `requirements/` | 需求工作区（存档/读档，跨会话恢复） |
| `.specify/templates/` | 文档模板（仅参考，非强制） |
| `.specify/memory/constitution.md` | 项目原则权威来源 |
