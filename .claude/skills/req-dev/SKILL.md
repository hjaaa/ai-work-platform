---
name: req-dev
description: 需求开发的统一入口。自动判断当前阶段，加载历史经验，执行需求分析/方案设计/任务拆解/编码实施，完成后触发知识沉淀。用户只需表达意图，Agent 自主路由。
metadata:
  author: ai-work-platform
---

## User Input

```text
$ARGUMENTS
```

## 设计理念

> "不是因为功能少，而是把复杂性藏到了 Agent 的智能路由里。"
> 用户不需要记住十几个命令，只需要表达意图。

本 skill 是需求开发的**唯一入口**，覆盖从"想法"到"可运行代码"的全过程。
Agent 根据当前状态自动判断该做什么，用户无需手动选择阶段。

## 执行流程

### Step 1: 解析意图与上下文

**如果 `$ARGUMENTS` 不为空**：这是一个新需求或对已有需求的续作指令。

**如果 `$ARGUMENTS` 为空**：检查最近的需求工作区，尝试恢复。

确定工作模式：
- **新需求**：`$ARGUMENTS` 描述了一个新功能/修复/重构
- **继续已有需求**：`$ARGUMENTS` 中包含"继续"/"恢复"，或指定了 requirement-id
- **变更需求**：`$ARGUMENTS` 中包含"改一下"/"调整"/"需求变了"

### Step 2: 加载历史经验（消除冷启动）

**无论哪种工作模式，都必须先执行此步骤。**

**经验检索策略**（根据 experience/ 规模选择）：

- **experience/ 条目 ≤ 5 个**：直接在主对话中读取 `index.md` 并按需加载匹配文件（轻量，不值得派发 Agent）
- **experience/ 条目 > 5 个**：派发 `experience-searcher` Agent（sonnet 模型，独立上下文），传入需求关键词，接收精简摘要（< 1000 tokens）

无论哪种方式，最终展示格式相同：

```
## 📋 历史经验提醒

基于关键词 [{匹配词}]，找到以下相关经验：

⚠️ 已知坑点：
- [坑点描述]（置信度：高/中/低）

📋 建议检查：
- [检查项]

🔴 风险提醒：（如果 risk-rules 命中）
- [风险类型]：[具体提醒]
```

如果没有匹配，简要说明"未找到相关历史经验，这可能是一个新场景"，然后继续。
**不要在没有匹配时输出大段空模板。**

### Step 3: 判断阶段并路由执行

根据当前状态自动判断：

#### 路由 A：新需求（没有 requirements/{id}/ 目录）

1. 从 `$ARGUMENTS` 生成简短有意义的 requirement-id
2. 创建 `requirements/{id}/` 目录，从 `.template/` 复制模板文件
3. 填写 `meta.yaml`（status: in_progress, current_phase: analysis）
4. **需求分析**：
   - 结合历史经验和风险提醒，与用户对话明确需求
   - **必须生成 `spec.md`**，参考 `.specify/templates/spec-template.md` 的结构
   - 简单需求可以精简章节，但文件必须存在，确保留痕
5. 更新 `process.txt`
6. 问用户："需求文档已生成，要继续做方案吗？"

#### 路由 B：有需求，没有方案

**方案设计策略**（根据需求复杂度选择）：

- **简单需求**（单模块、单服务、无架构影响）：直接在主对话中设计方案
- **复杂需求**（跨服务、涉及数据模型变更、架构影响）：派发 `planner` Agent（opus 模型，独立上下文），传入 spec.md 内容，接收精简的方案建议（< 1500 tokens），基于其建议在主对话中完成 plan.md

无论哪种方式：
1. 加载 `context/project/{相关服务}/` 的项目知识（如果存在）
2. 加载 `context/team/coding-standards.md`
3. **必须生成 `plan.md`**，参考 `.specify/templates/plan-template.md`
   - 简单需求可以精简（如只保留实现思路和关键决策），但文件必须存在
4. 更新 `meta.yaml`（current_phase: design）和 `process.txt`
5. 问用户："方案文档已生成，要开始实施吗？"

#### 路由 C：有方案，准备实施

1. 加载 `context/team/coding-standards.md` + `context/team/test-standards.md`
2. 如果 experience/ 中有相关场景的 `self-check.md`，加载并展示检查清单
3. **任务拆解与实施**：
   - 按用户故事拆解任务
   - TDD：先写测试，再写实现
   - 每完成一个里程碑，更新 `process.txt`
   - 遇到意外问题，记录到 `notes.md`
4. 更新 `meta.yaml`（current_phase: implementation）

#### 路由 D：恢复中断的需求

1. 找到目标需求的 `requirements/{id}/` 目录
2. 读取 `meta.yaml`（知道在哪个阶段）
3. 读取 `process.txt`（关键：包含 What Worked / What Did NOT Work / Exact Next Step / Files State）
4. 读取 `notes.md`（知道发现了什么）
5. 向用户输出结构化恢复简报（**不自动开始工作，等待用户确认**）：

```
## 会话恢复

**需求**：{title}
**阶段**：{current_phase}
**状态**：{status}

**上次进展**：
- [从 What Worked 提取关键进展]

**走不通的路**（不要再试）：
- [从 What Did NOT Work 提取]

**下一步**：
- [从 Exact Next Step 提取]

确认继续？
```

6. 用户确认后，根据 `current_phase` 进入对应路由（B/C/E）

#### 路由 E：需求变更

1. 找到目标需求目录
2. 只更新受影响的文档（spec/plan），不重走全流程
3. 如果变更影响已完成的代码，标记需要修改的部分
4. 更新 `process.txt` 记录变更

#### 路由 F：实施完成

1. 确认代码和测试都已完成
2. **自动代码审查**（条件触发）：
   - 检测本次是否有 Java/XML 文件变更（通过 `git diff --name-only` 检查）
   - **有代码变更** → 派发 `code-reviewer` Agent（sonnet 模型，独立上下文）
     - 传入变更文件列表和 diff 内容
     - 接收审查报告（< 2000 tokens）
     - 展示报告给用户
     - 如果有 ERROR 级别问题：提示用户修复后再标记完成
     - 如果只有 WARN 或无问题：继续下一步
   - **没有代码变更**（只改了文档/配置）→ 跳过审查
3. 更新 `meta.yaml`（current_phase: done, status: completed）
4. **触发知识沉淀**：
   - 检查 `notes.md` 是否有待沉淀的内容
   - 回顾本次对话中用户纠正过 AI 的地方
   - 询问用户：
     ```
     需求已完成。是否沉淀本次开发经验？
     1. 是 — 我来引导你完成沉淀（执行 /optimize-flow）
     2. 跳过 — 没有需要记录的经验
     ```
   - 如果用户选择沉淀，执行 `/optimize-flow` 的流程

### Step 4: 持续更新进度

在整个执行过程中：

- **每完成一个阶段**：更新 `meta.yaml` 的 current_phase，并更新 `process.txt` 的以下区域：
  - `Current State`：更新 status 和 phase
  - `What Worked`：追加已验证有效的方案
  - `Exact Next Step`：更新为下一步的具体操作
  - `Files State`：更新每个文件的状态

- **尝试方案失败时**：立即记录到 `process.txt` 的 `What Did NOT Work` 区域
  - 格式：`- 尝试 [方案] → 失败，因为 [原因]（不要再试）`
  - **这是最重要的记录**——防止恢复会话后 AI 重蹈覆辙

- **AI 犯错被纠正时**：立即记录到 `notes.md` 的 `What Did NOT Work` 区域
- **发现新技术约束时**：记录到 `notes.md` 的 `技术发现` 区域

## 行为规则

- **不强制阶段顺序**：用户说"直接写代码"也行，但 spec.md 和 plan.md 必须补齐（可以后补）
- **spec.md 和 plan.md 是强制留痕**：每个需求都必须生成这两个文件，简单需求可以精简内容但文件必须存在
- **复杂度匹配文档详细度**：简单需求的 spec 可以只有几行，复杂需求参考完整模板
- **主动但不啰嗦**：风险提醒必须主动，但不要每句话都加 disclaimer
- **经验检索结果为空时不要大段输出**：一句话带过即可
