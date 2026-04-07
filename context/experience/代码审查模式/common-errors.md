## 2026-04-07 Controller 直接注入 Mapper 绕过 Service 层

- **现象**：GenerationController 直接注入 GenerationMapper 和 DeploymentMapper，在 Controller 中构建 LambdaQueryWrapper 执行查询
- **原因**：AI 图省事，觉得"只是简单查询"不需要 Service 层包装
- **正确做法**：无论查询多简单，Controller 必须通过 Service 层访问数据。即使只是透传，也要创建 Service 方法
- **适用场景**：所有 Controller 开发，遵循 Constitution 原则 I

## 2026-04-07 外部进程执行逻辑多处重复实现

- **现象**：ClaudeCodeOrchestrator、TestRunnerService、DeployService 三处各自实现了 ProcessBuilder → 读输出 → 超时 → 异常处理
- **原因**：各模块独立开发时没有意识到共同模式，缺少"先检查是否有可复用工具"的意识
- **正确做法**：外部进程执行统一使用 `ProcessExecutor` 工具类，支持静默/流式两种模式
- **适用场景**：任何需要调用外部命令的场景

## 2026-04-07 前端 highlight.js 多处重复注册语言

- **现象**：CodePreview.vue 和 ChatView.vue 各自 import 并 registerLanguage，运行时重复注册
- **原因**：两个组件独立开发，没有检查是否已有共享初始化
- **正确做法**：第三方库的初始化配置提取到 `src/utils/` 单例模块，各组件 import 该实例
- **适用场景**：任何需要全局初始化的前端库（highlight.js、markdown-it、dayjs 等）

## 2026-04-07 WebSocket 单例切换项目时旧连接未断开

- **现象**：模块级 stompClient 变量在 connectWebSocket 时直接覆盖，旧连接 subscription 未注销
- **原因**：初始实现只考虑了单项目场景，没有考虑切换项目的边界
- **正确做法**：connectWebSocket 开头先调用 disconnectWebSocket() 断开旧连接
- **适用场景**：所有使用模块级单例管理连接/资源的前端代码
