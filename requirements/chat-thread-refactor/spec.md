# 需求规格：对话模块重构 — 引入线程（Thread）概念

**需求 ID**: chat-thread-refactor  
**创建时间**: 2026-04-08  
**状态**: Draft

## 背景与目标

### 现状

当前对话模块的数据模型是**扁平的**：

- `conversation` 表中每条消息通过 `project_id` 关联到项目
- 一个项目的所有对话消息在一个连续流中，无法区分不同话题
- 前端路由 `/project/:projectId` 直接进入唯一的对话页面

### 目标

参考截图中的 UI 结构（类似 ChatGPT/Cursor），重构为**项目 → 线程 → 消息**的三层结构：

```
Project
├── Thread 1 ("查询手撕鸡 formated_output")   ← 21分钟前
├── Thread 2 ("回复问候加速和调试代码?")        ← 55分钟前
├── Thread 3 ("梳理 git worktree 最佳实践")    ← 1周
└── Thread N ...
```

核心变化：
1. 新增 `chat_thread` 表，作为项目和消息之间的中间层
2. `conversation` 表新增 `thread_id` 字段，消息归属到线程
3. 前端侧边栏展示项目下的线程列表，主区域展示选中线程的对话
4. WebSocket 订阅粒度从 project 级别细化到 thread 级别

---

## 用户故事

### User Story 1 — 在项目下创建新线程并对话 (P1)

用户进入某个项目后，点击"新线程"按钮创建一条新的对话线程，在该线程中与 AI 进行对话。

**为什么是 P1**：这是最核心的功能，没有线程就无法分离对话。

**独立测试**：创建项目 → 创建线程 → 发送消息 → 收到 AI 回复 → 消息只出现在该线程中。

**验收场景**：

1. **Given** 用户在项目页面，**When** 点击"新线程"，**Then** 创建一个新线程并自动进入对话界面
2. **Given** 用户在某线程中，**When** 发送消息，**Then** 消息持久化到该线程下，AI 回复也归属该线程
3. **Given** 用户在某线程中，**When** AI 回复完成，**Then** 线程标题自动根据首条消息内容生成

---

### User Story 2 — 查看和切换线程列表 (P1)

用户在左侧边栏看到当前项目下的所有线程列表，按最后活跃时间倒序排列，点击切换到不同线程。

**为什么是 P1**：线程列表是导航的基础，和 Story 1 共同构成 MVP。

**独立测试**：创建多个线程 → 侧边栏显示列表 → 点击切换 → 消息区域更新为对应线程内容。

**验收场景**：

1. **Given** 项目有多个线程，**When** 用户进入项目，**Then** 左侧显示线程列表，按 `updatedAt` 倒序
2. **Given** 线程列表展示中，**When** 用户点击某个线程，**Then** 右侧消息区加载该线程的历史消息
3. **Given** 用户在线程 A 对话中，**When** 切换到线程 B，**Then** 线程 A 的 WebSocket 订阅断开，线程 B 的订阅建立

---

### User Story 3 — 线程管理（重命名、删除）(P2)

用户可以重命名线程标题，或删除不需要的线程。

**为什么是 P2**：管理功能非核心，但提升体验。

**独立测试**：右键线程 → 重命名/删除 → 验证生效。

**验收场景**：

1. **Given** 线程列表中，**When** 用户对某线程执行重命名操作，**Then** 线程标题更新
2. **Given** 线程列表中，**When** 用户删除某线程，**Then** 线程及其下所有消息逻辑删除，列表刷新

---

### User Story 4 — 历史数据兼容迁移 (P1)

已有的 `conversation` 记录（没有 `thread_id`）需要平滑迁移，不丢失数据。

**为什么是 P1**：数据兼容是上线的前提。

**验收场景**：

1. **Given** 数据库中有旧 conversation 记录，**When** 执行 Flyway 迁移，**Then** 为每个 project 创建一个默认线程，将该项目的所有旧消息关联到默认线程

---

### 边界情况

- 项目没有任何线程时：进入项目自动创建第一个线程
- 线程标题为空时：显示为"新对话"，等第一条消息发送后自动生成标题
- 并发创建线程：使用自增 ID + project_id 保证唯一性，无冲突
- 删除正在使用的线程：弹窗确认，删除后跳转到下一个线程或创建新线程

---

## 功能需求

### FR-001：新增 `chat_thread` 实体和表

系统必须新增 `chat_thread` 表，字段包括：
- `id` (BIGINT, 自增主键)
- `thread_id` (VARCHAR 64, 业务唯一标识，类似 projectId 的生成规则)
- `project_id` (VARCHAR 64, 关联项目，NOT NULL)
- `title` (VARCHAR 256, 线程标题，默认为"新对话")
- `created_at` (DATETIME, 创建时间)
- `updated_at` (DATETIME, 最后活跃时间，每次新消息时更新)
- `deleted` (TINYINT, 逻辑删除)

索引：`idx_project_id_updated` (project_id, updated_at DESC)

### FR-002：`conversation` 表新增 `thread_id` 字段

- 新增 `thread_id` VARCHAR(64) NOT NULL（Flyway 迁移时先允许 NULL，迁移完数据后再设 NOT NULL）
- 新增索引 `idx_thread_id` (thread_id)

### FR-003：线程 CRUD API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/projects/{projectId}/threads` | 创建新线程 |
| GET | `/api/projects/{projectId}/threads` | 获取项目的线程列表（按 updatedAt 倒序） |
| PUT | `/api/threads/{threadId}` | 更新线程标题 |
| DELETE | `/api/threads/{threadId}` | 逻辑删除线程及其消息 |

### FR-004：对话 API 改造

| 原 API | 新 API | 变化 |
|--------|--------|------|
| GET `/api/projects/{projectId}/conversations` | GET `/api/threads/{threadId}/messages` | 按线程查询消息 |
| POST `/api/projects/{projectId}/chat` | POST `/api/threads/{threadId}/chat` | 在指定线程中发送消息 |

旧 API 暂时保留并标记 `@Deprecated`，返回所有线程的合并消息（兼容过渡）。

### FR-005：WebSocket 订阅粒度调整

- 新订阅路径：`/topic/thread/{threadId}`（按线程推送）
- 保留 `/topic/project/{projectId}` 用于项目级通知（如部署状态）
- MessagePushService 新增 `pushToThread(threadId, message)` 方法

### FR-006：线程标题自动生成

- 新线程创建时标题为"新对话"
- 当第一条用户消息发送后，截取消息前 30 个字符作为线程标题
- 后续不再自动更新标题（用户可手动重命名）

### FR-007：数据迁移

Flyway 迁移脚本（V5）：
1. 创建 `chat_thread` 表
2. `conversation` 表新增 `thread_id` 列（允许 NULL）
3. 为每个已有 `project_id` 在 `conversation` 中的记录，创建一个默认线程
4. 将对应 `conversation` 记录的 `thread_id` 更新为默认线程的 `thread_id`
5. 将 `thread_id` 改为 NOT NULL

---

## 关键实体

- **ChatThread**：对话线程，归属于 Project，包含多条 Conversation
- **Conversation**：对话消息（已有），新增 `threadId` 字段
- **Project**：项目（已有），不修改，通过 ChatThread 间接关联对话

实体关系：
```
Project 1 ←→ N ChatThread 1 ←→ N Conversation
```

---

## 风险提醒

🔴 **数据安全**：涉及 `conversation` 表结构变更和数据迁移，需确认：
- Flyway 迁移脚本的事务性（MySQL DDL 不支持回滚，需特别注意顺序）
- 迁移前备份策略
- `thread_id` NOT NULL 约束的添加时机（先迁数据，再加约束）

🔴 **接口变更兼容性**：
- 旧的 `/api/projects/{projectId}/conversations` 和 `/chat` 接口需保留过渡期
- WebSocket 订阅路径变更需前后端同步发布

---

## 成功标准

- **SC-001**：所有对话消息必须归属于某个线程，线程必须归属于某个项目
- **SC-002**：已有数据迁移后零丢失，旧消息可通过默认线程访问
- **SC-003**：前端可在项目内自由创建、切换、管理线程
- **SC-004**：WebSocket 推送按线程隔离，不同线程的消息互不干扰

---

## 假设

- 不引入新的外部依赖，使用现有技术栈（Spring Boot + MyBatis-Plus + Vue 3 + Element Plus）
- 不涉及用户认证/权限变更（暂时所有线程对所有用户可见）
- 线程数量级：单项目数十到百级，不需要分页（列表一次性加载）
- 前端右侧预览区（PRD/代码/测试/部署）功能不变，仍按项目维度
