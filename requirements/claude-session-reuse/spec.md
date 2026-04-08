# Claude CLI 会话复用架构 + ESC 取消

## 背景与痛点

当前架构每次发消息都启动一个全新的 Claude CLI 进程（`claude -p "prompt" --output-format text`），存在以下问题：

1. **无上下文复用**：每次都是全新进程，`buildPrompt()` 需要手动把历史拼进 prompt，随对话增长会撞 token 上限
2. **无法中断**：用户发出消息后只能等待 AI 完成，无法取消
3. **性能浪费**：每次启动新进程有初始化开销，且历史越长 prompt 越大

## 目标

1. **会话复用**：利用 Claude CLI 的 `--session-id` + `--resume` 机制，让 CLI 自己管理对话历史
2. **ESC 取消**：对话过程中用户按 ESC 键可中断当前 AI 回复
3. **流式输出优化**：使用 `--output-format stream-json` 获取结构化流式输出

## 技术调研结论

Claude CLI 支持：
- `--session-id <uuid>`：指定会话 ID（首次自动创建）
- `--resume <session-id>`：恢复指定会话，自动携带历史上下文
- `--output-format stream-json`：输出 JSON 流，包含 type/message/result 等结构化事件
- `--verbose`：stream-json 模式必须开启

验证结果：`--resume` 后 Claude 能正确引用之前的对话内容，上下文完整保留。

## 范围

### 包含
- 后端：ClaudeCodeOrchestrator 改造为 session 模式
- 后端：新增 cancel 接口，支持终止运行中的进程
- 后端：stream-json 输出解析
- 前端：ESC 键监听 + cancel API 调用
- 前端：取消状态 UI 反馈

### 不包含
- 长驻进程模式（stdin/stdout streaming）— 复杂度过高，后续再评估
- 多轮对话的 token 计费统计
- 会话过期/清理策略（后续需求）

## 约束
- 保持现有 WebSocket 推送机制不变
- 兼容现有数据库对话记录结构
- `@Async` 方法需注意代理问题（已有经验）
