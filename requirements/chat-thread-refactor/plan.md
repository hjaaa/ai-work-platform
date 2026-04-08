# 实施方案：对话模块重构 — 引入线程（Thread）概念

**需求 ID**: chat-thread-refactor | **日期**: 2026-04-08 | **Spec**: spec.md

## 总结

将当前"项目 → 消息"的扁平对话模型重构为"项目 → 线程 → 消息"的三层结构。核心变更包括：新增 `chat_thread` 表和实体、`conversation` 表加 `thread_id`、新增线程 CRUD 服务和 API、WebSocket 推送从项目级细化到线程级、前端新增线程侧边栏。

## 技术上下文

**技术栈**: Spring Boot 3.5.13 + MyBatis-Plus 3.5.15 + Vue 3 + Element Plus  
**存储**: MySQL (Flyway 管理迁移)  
**实时通信**: STOMP over WebSocket  
**测试**: JUnit5 + Mockito

## 架构决策

### AD-1：thread_id 生成策略

采用 `UUID.randomUUID().toString().replace("-", "").substring(0, 16)` 生成 16 位随机 ID，与现有 `projectId` 生成方式保持一致。

### AD-2：WebSocket 双层订阅

保留 `/topic/project/{projectId}` 用于项目级通知（部署状态等），新增 `/topic/thread/{threadId}` 用于对话消息推送。前端切换线程时只需重新订阅线程 topic，不影响项目级订阅。

### AD-3：旧 API 兼容策略

旧的 `GET /api/projects/{projectId}/conversations` 和 `POST /api/projects/{projectId}/chat` 标记 `@Deprecated`，保留 1 个版本周期。旧 chat 接口自动路由到该项目的最新线程。

### AD-4：数据迁移分步执行

Flyway V5 脚本分 5 步：建表 → 加列 → 插入默认线程 → 更新 thread_id → 加 NOT NULL 约束。由于 MySQL DDL 不支持事务回滚，需提前备份。

---

## 分层变更清单

### 第一层：数据库 + Domain（基础层）

#### 1.1 Flyway 迁移脚本 V5

**文件**: `platform-web/src/main/resources/db/migration/V5__add_chat_thread.sql`

```sql
-- 1. 创建 chat_thread 表
CREATE TABLE chat_thread (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    thread_id VARCHAR(64) NOT NULL UNIQUE,
    project_id VARCHAR(64) NOT NULL,
    title VARCHAR(256) DEFAULT '新对话',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0,
    INDEX idx_project_updated (project_id, updated_at DESC)
) COMMENT '对话线程表';

-- 2. conversation 表新增 thread_id（先允许 NULL）
ALTER TABLE conversation ADD COLUMN thread_id VARCHAR(64) NULL AFTER project_id;
ALTER TABLE conversation ADD INDEX idx_thread_id (thread_id);

-- 3. 为每个已有项目创建默认线程
INSERT INTO chat_thread (thread_id, project_id, title, created_at, updated_at)
SELECT 
    CONCAT('legacy_', project_id) AS thread_id,
    project_id,
    '历史对话' AS title,
    MIN(created_at) AS created_at,
    MAX(created_at) AS updated_at
FROM conversation
WHERE deleted = 0
GROUP BY project_id;

-- 4. 将旧 conversation 关联到对应的默认线程
UPDATE conversation c
JOIN chat_thread t ON c.project_id = t.project_id AND t.thread_id LIKE 'legacy_%'
SET c.thread_id = t.thread_id
WHERE c.thread_id IS NULL;

-- 5. thread_id 设为 NOT NULL
ALTER TABLE conversation MODIFY thread_id VARCHAR(64) NOT NULL;
```

#### 1.2 ChatThread 实体

**新增文件**: `platform-domain/src/main/java/com/aiworkplatform/domain/entity/ChatThread.java`

```java
@Data
@TableName("chat_thread")
public class ChatThread {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String threadId;
    private String projectId;
    private String title;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
```

#### 1.3 Conversation 实体修改

**修改文件**: `platform-domain/src/main/java/com/aiworkplatform/domain/entity/Conversation.java`

新增字段：
```java
private String threadId;
```

#### 1.4 ChatThreadMapper

**新增文件**: `platform-domain/src/main/java/com/aiworkplatform/domain/mapper/ChatThreadMapper.java`

```java
public interface ChatThreadMapper extends BaseMapper<ChatThread> {
}
```

---

### 第二层：Service（业务逻辑层）

#### 2.1 新增 ChatThreadService 接口 + 实现

**新增文件**:
- `platform-service/src/main/java/com/aiworkplatform/service/thread/ChatThreadService.java`
- `platform-service/src/main/java/com/aiworkplatform/service/thread/impl/ChatThreadServiceImpl.java`

**接口方法**：

```java
public interface ChatThreadService {
    // 创建新线程
    ChatThread createThread(String projectId);
    
    // 获取项目的线程列表（按 updatedAt 倒序）
    List<ChatThread> listByProject(String projectId);
    
    // 获取单个线程
    ChatThread getByThreadId(String threadId);
    
    // 更新线程标题
    void updateTitle(String threadId, String title);
    
    // 删除线程（逻辑删除线程 + 关联的消息）
    void deleteThread(String threadId);
    
    // 获取或创建项目的默认线程（用于旧 API 兼容）
    ChatThread getOrCreateDefaultThread(String projectId);
}
```

**实现要点**：
- `createThread`：生成 threadId（16位随机），title 默认"新对话"
- `listByProject`：按 `updatedAt DESC` 排序
- `deleteThread`：同时逻辑删除 `chat_thread` 和该线程下的 `conversation`
- `getOrCreateDefaultThread`：查找项目最新的线程，如果没有则自动创建

#### 2.2 ChatService 接口改造

**修改文件**: `platform-service/src/main/java/com/aiworkplatform/service/chat/ChatService.java`

```java
public interface ChatService {
    // 新接口：按线程处理消息
    void handleUserMessage(String threadId, String content);
    
    // 新接口：按线程获取历史
    List<Conversation> getHistoryByThread(String threadId);
    
    // 旧接口保留（标记 @Deprecated，内部路由到默认线程）
    @Deprecated
    void handleUserMessageByProject(String projectId, String content);
    
    @Deprecated
    List<Conversation> getHistory(String projectId);
}
```

#### 2.3 ChatServiceImpl 改造

**修改文件**: `platform-service/src/main/java/com/aiworkplatform/service/chat/impl/ChatServiceImpl.java`

核心变更：
- `saveConversation` 增加 `threadId` 参数
- `handleUserMessage(threadId, content)`：
  1. 通过 threadId 获取 ChatThread → 获取 projectId
  2. 保存消息时写入 threadId
  3. 更新 ChatThread 的 updatedAt
  4. 如果是线程的第一条用户消息，自动生成线程标题（前30字符）
- `getHistoryByThread(threadId)`：按 threadId 查询消息
- 旧方法 `handleUserMessageByProject` 内部调用 `chatThreadService.getOrCreateDefaultThread(projectId)` 获取 threadId，然后转发

#### 2.4 MessagePushService 改造

**修改文件**: `platform-service/src/main/java/com/aiworkplatform/service/chat/MessagePushService.java`

新增方法：
```java
// 按线程推送消息
public void pushToThread(String threadId, ChatMessage message) {
    String destination = "/topic/thread/" + threadId;
    messagingTemplate.convertAndSend(destination, message);
}

public void pushAssistantMessageToThread(String threadId, String content) { ... }
public void pushProgressToThread(String threadId, String content) { ... }
```

#### 2.5 ContextWindowManager 改造

**修改文件**: `platform-service/src/main/java/com/aiworkplatform/service/chat/ContextWindowManager.java`

无需改动逻辑。输入从"项目全部消息"变为"线程消息"，但 `buildContext(List<Conversation>)` 方法签名不变，调用方传入的数据范围变窄即可。

#### 2.6 ChatMessage 改造

**修改文件**: `platform-service/src/main/java/com/aiworkplatform/service/chat/ChatMessage.java`

新增字段：
```java
private String threadId;
```

---

### 第三层：Web（控制器层）

#### 3.1 新增 ChatThreadController

**新增文件**: `platform-web/src/main/java/com/aiworkplatform/web/controller/ChatThreadController.java`

```java
@RestController
@RequestMapping("/api")
public class ChatThreadController {
    
    // POST /api/projects/{projectId}/threads — 创建线程
    // GET  /api/projects/{projectId}/threads — 线程列表
    // PUT  /api/threads/{threadId} — 更新标题
    // DELETE /api/threads/{threadId} — 删除线程
    // GET  /api/threads/{threadId}/messages — 获取线程消息
    // POST /api/threads/{threadId}/chat — 在线程中发送消息
}
```

#### 3.2 ProjectController 旧接口标记废弃

**修改文件**: `platform-web/src/main/java/com/aiworkplatform/web/controller/ProjectController.java`

- `getConversations` 和 `chat` 方法加 `@Deprecated` 注解
- 内部实现改为路由到默认线程

#### 3.3 ChatWebSocketController 改造

**修改文件**: `platform-web/src/main/java/com/aiworkplatform/web/websocket/ChatWebSocketController.java`

新增按线程发送的 MessageMapping：
```java
@MessageMapping("/chat/thread/{threadId}")
public void handleThreadMessage(@DestinationVariable String threadId, @Payload ChatMessage message) {
    chatService.handleUserMessage(threadId, message.getContent());
}
```

#### 3.4 DTO 新增

**新增文件**: `platform-web/src/main/java/com/aiworkplatform/web/dto/UpdateThreadRequest.java`

```java
public class UpdateThreadRequest {
    @NotBlank
    private String title;
}
```

---

### 第四层：前端

#### 4.1 新增 thread.js API 模块

**新增文件**: `platform-frontend/src/api/thread.js`

```javascript
export function createThread(projectId) { ... }
export function listThreads(projectId) { ... }
export function updateThread(threadId, title) { ... }
export function deleteThread(threadId) { ... }
export function getThreadMessages(threadId) { ... }
export function sendThreadMessage(threadId, message) { ... }
```

#### 4.2 WebSocket 改造

**修改文件**: `platform-frontend/src/api/websocket.js`

新增函数：
```javascript
// 订阅线程消息（切换线程时调用）
export function subscribeThread(threadId, onMessage) { ... }

// 取消订阅当前线程
export function unsubscribeThread() { ... }
```

连接生命周期变更：
- `connectWebSocket(projectId)` 只建立连接，不订阅具体线程
- 订阅线程通过 `subscribeThread(threadId, callback)` 单独管理
- 切换线程时：先 `unsubscribeThread()`，再 `subscribeThread(newThreadId)`

#### 4.3 路由调整

**修改文件**: `platform-frontend/src/router/index.js`

```javascript
// 新增线程路由（嵌套在项目下）
{
  path: '/project/:projectId',
  component: () => import('../views/ChatView.vue'),
  children: [
    { path: '', name: 'ProjectChat', redirect: to => ({ name: 'Thread', params: { ...to.params } }) },
    { path: 'thread/:threadId', name: 'Thread', component: () => import('../views/ChatView.vue') }
  ]
}
```

或更简单的方案（推荐）：保持现有路由结构，通过 query 参数传递 threadId：
```
/project/:projectId?thread=xxx
```

线程切换不需要路由跳转，在组件内部管理 `currentThreadId` 状态即可。

#### 4.4 ChatView.vue 重构

**修改文件**: `platform-frontend/src/views/ChatView.vue`

布局变更（三栏）：
```
┌──────────────┬──────────────────────┬──────────────────┐
│  线程侧边栏   │    对话主区域          │   预览面板        │
│              │                      │   (PRD/代码/...)  │
│ [+ 新线程]    │  消息列表             │                  │
│              │                      │                  │
│ 线程1 (活跃)  │                      │                  │
│ 线程2        │                      │                  │
│ 线程3        │  ──────────────────  │                  │
│ ...          │  [输入框] [发送]       │                  │
└──────────────┴──────────────────────┴──────────────────┘
```

核心状态变更：
```javascript
const threads = ref([])           // 线程列表
const currentThreadId = ref(null)  // 当前选中线程
const messages = ref([])           // 当前线程的消息

// 加载线程列表
async function loadThreads() { ... }

// 切换线程
async function switchThread(threadId) {
    unsubscribeThread()
    currentThreadId.value = threadId
    await loadMessages(threadId)
    subscribeThread(threadId, onMessage)
}

// 创建新线程
async function createNewThread() {
    const thread = await createThread(projectId)
    threads.value.unshift(thread)
    switchThread(thread.threadId)
}
```

#### 4.5 线程侧边栏组件

**新增文件**: `platform-frontend/src/components/ThreadSidebar.vue`

功能：
- 展示线程列表（标题 + 相对时间）
- 支持创建新线程
- 右键菜单：重命名、删除
- 当前选中线程高亮
- 线程标题过长时省略号截断

---

## 实施顺序（Task 拆解）

### Phase 1: 数据层（后端基础）
1. 编写 Flyway V5 迁移脚本
2. 新增 ChatThread 实体 + Mapper
3. 修改 Conversation 实体（加 threadId）
4. 新增 ChatThreadService 接口 + 实现 + 单元测试

### Phase 2: 服务层改造
5. ChatService 接口新增线程方法
6. ChatServiceImpl 改造（按线程查询/保存/标题自动生成）+ 单元测试
7. MessagePushService 新增 pushToThread + 单元测试
8. ChatMessage 新增 threadId 字段

### Phase 3: Web 层
9. 新增 ChatThreadController + DTO
10. ProjectController 旧接口标记 @Deprecated 并路由到默认线程
11. ChatWebSocketController 新增线程级 MessageMapping

### Phase 4: 前端
12. 新增 thread.js API 模块
13. 改造 websocket.js（线程级订阅）
14. 新增 ThreadSidebar.vue 组件
15. 重构 ChatView.vue（三栏布局 + 线程切换）
16. 路由调整

### Phase 5: 集成验证
17. 端到端测试：创建线程 → 发消息 → 切换线程 → 消息隔离
18. 数据迁移验证：旧数据通过默认线程可正常访问
19. WebSocket 推送验证：消息只推送到对应线程订阅者

---

## 影响范围

| 维度 | 影响 |
|------|------|
| 数据库 | 新增 `chat_thread` 表，`conversation` 表加 `thread_id` 列 |
| 后端模块 | platform-domain（实体+Mapper）、platform-service（Service 改造）、platform-web（Controller + WebSocket） |
| 前端 | ChatView.vue 重构、新增 ThreadSidebar.vue、新增 thread.js API、websocket.js 改造 |
| API | 新增 6 个 API，2 个旧 API 标记废弃 |
| WebSocket | 新增 `/topic/thread/{threadId}` 订阅路径 |

## 回滚方案

- Flyway 迁移脚本提供对应的 U5 undo 脚本
- 前端通过旧 API 仍可正常工作（旧 API 保留）
- 如需回滚：执行 U5 回滚脚本 → 部署旧版后端 → 部署旧版前端
