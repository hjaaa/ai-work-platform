# 方案设计：Claude CLI 会话复用 + ESC 取消

## 核心思路

用 Claude CLI 原生的 `--session-id` / `--resume` 取代手动拼 prompt，由 CLI 自己管理对话历史。
每次消息仍是独立进程（非长驻），但 session 复用让上下文完整保留。
通过跟踪运行中的 Process 对象实现 ESC 取消。

## 变更清单

### 1. 数据库：chat_thread 新增 claude_session_id

**文件**：`V6__add_claude_session_id.sql`

```sql
ALTER TABLE chat_thread ADD COLUMN claude_session_id VARCHAR(64) NULL 
  COMMENT 'Claude CLI 会话 ID，首次对话时生成';
```

- 允许 NULL（旧线程无 session，发新消息时自动生成）
- 不需要唯一索引（一个线程对应一个 session）

### 2. 实体：ChatThread 新增字段

**文件**：`ChatThread.java`

```java
private String claudeSessionId;
```

### 3. ChatThreadService：新增 updateSessionId 方法

**文件**：`ChatThreadService.java` + `ChatThreadServiceImpl.java`

```java
void updateClaudeSessionId(String threadId, String sessionId);
```

### 4. ClaudeCodeOrchestrator 改造（核心）

**文件**：`ClaudeCodeOrchestrator.java`

改动点：
- 新增 `ConcurrentHashMap<String, Process> runningProcesses` 按 threadId 跟踪进程
- `execute()` 方法签名改为 `execute(String threadId, Path workDir, String prompt, String sessionId)`
  - `sessionId == null` → 首次，用 `--session-id <新生成UUID>`
  - `sessionId != null` → 后续，用 `--resume <sessionId>`
- 返回值从 `String` 改为自定义 `ExecutionResult`（包含 output + sessionId）
- 新增 `cancel(String threadId)` → `process.destroyForcibly()`
- 解析 stream-json 输出，提取 assistant message 和 result

**stream-json 输出格式**（已验证）：
```json
{"type":"assistant","message":{"content":[{"type":"text","text":"..."}],...}}
{"type":"result","subtype":"success","result":"完整回复","session_id":"xxx",...}
```

关键：从 `result` 事件中提取 `session_id` 和 `result`。

### 5. ProcessExecutor 支持取消

**文件**：`ProcessExecutor.java`

新增重载：
```java
public static String execute(Path workDir, int timeoutMinutes,
                             Consumer<String> lineConsumer,
                             AtomicReference<Process> processRef,
                             String... command)
```

- `processRef` 在 `process = pb.start()` 后立即 set
- 外部可通过 `processRef.get().destroyForcibly()` 取消

### 6. ChatServiceImpl 改造

**文件**：`ChatServiceImpl.java`

改动点：
- 移除 `ContextWindowManager` 依赖（不再手动拼历史）
- 移除 `buildPrompt()` 方法
- `handleThreadMessage()` 流程变为：
  1. 持久化用户消息
  2. 自动生成标题
  3. 从 chat_thread 读 `claudeSessionId`
  4. 调用 `orchestrator.execute(threadId, workDir, content, sessionId)`
  5. 如果是首次（sessionId 为 null），从返回结果中取 sessionId 存入 chat_thread
  6. 持久化 AI 回复 + 推送
- prompt 只传用户当前消息，不再拼历史
- 新增 `cancelThread(String threadId)` 方法

### 7. ChatService 接口新增 cancel

**文件**：`ChatService.java`

```java
void cancelThread(String threadId);
```

### 8. ChatThreadController 新增 cancel 接口

**文件**：`ChatThreadController.java`

```java
@PostMapping("/threads/{threadId}/cancel")
public Result<Void> cancel(@PathVariable String threadId)
```

### 9. 前端：ESC 取消 + API

**文件**：`thread.js`
```javascript
export function cancelThread(threadId) {
  return http.post(`/threads/${threadId}/cancel`)
}
```

**文件**：`ChatView.vue`
- 监听 `keydown` ESC 事件（`loading === true` 时生效）
- 调用 `cancelThread(currentThreadId)`
- 重置 `loading = false`
- 推送"已取消"提示消息
- 输入框区域显示 `ESC 取消` 提示（仅 loading 时显示）

### 10. 清理

- `ContextWindowManager.java` — 暂时保留但标记 `@Deprecated`（其他地方可能还用）
- `ChatServiceImpl.buildPrompt()` — 删除
- orchestrator 原 `execute(String projectId, Path workDir, String prompt)` — 标记 `@Deprecated`

## 实施顺序

1. DB migration + Entity（基础）
2. ProcessExecutor 支持取消（工具层，可独立测试）
3. ClaudeCodeOrchestrator 改造（核心，依赖 2）
4. ChatThreadService + ChatServiceImpl 改造（业务层，依赖 1+3）
5. Controller cancel 接口（依赖 4）
6. 前端 ESC 功能（依赖 5）

## 风险点

| 风险 | 应对 |
|------|------|
| Claude CLI session 文件存储在本地，部署多实例时 session 不共享 | 当前单实例部署，后续可通过 `--session-id` 重建 |
| stream-json 格式可能随 CLI 版本变化 | 解析时做容错，非 JSON 行直接跳过 |
| 进程被 cancel 后 CLI session 状态是否完整 | 已验证：kill 进程后 session 仍可 resume，只是最后一条回复不完整 |
| `@Async` 方法需注意代理问题 | 沿用现有模式，cancel 方法放在 orchestrator 层（非 @Async） |

## 不做的事

- 不做长驻进程模式（复杂度过高）
- 不删除 conversation 表的持久化（保留用于 UI 展示历史、审计）
- 不改 WebSocket 推送机制
