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

## 2026-04-08 Controller 直接依赖 Service 实现类而非接口

- **现象**：所有 Controller 直接注入具体的 Service 类（如 `ProjectService` 是一个 `@Service` 标注的 class），没有接口层
- **原因**：AI 初始生成代码时图省事，直接写了 class 而非 interface + impl 的标准分层
- **正确做法**：Service 必须拆为接口 + 实现类。接口保持在原包路径（如 `service.project.ProjectService`），实现类放 `impl` 子包（如 `service.project.impl.ProjectServiceImpl`）。Controller 和其他 Service 依赖接口类型，Spring 按接口注入实现
- **适用场景**：新建任何 Service 类时，必须先建接口再建实现类

## 2026-04-08 实现业务逻辑后遗漏单元测试（第三次：2026-04-08 claude-session-reuse 重构）

- **现象**：AI 在执行 6 个 task 的完整重构（ProcessExecutor、ClaudeCodeOrchestrator、ChatServiceImpl 等）时，全程先写实现代码，最后才修复旧测试适配新签名。没有为任何新增逻辑（stream-json 解析、session 复用、cancel 机制）编写 TDD 驱动的测试
- **原因**：AI 将测试视为"事后验证"而非"设计工具"，尤其在多 task 连续实施时更容易跳过测试直接推进实现
- **正确做法**：采用 TDD 模式——先写测试（定义预期行为），再写实现（让测试通过）。具体流程：(1) 定义接口/方法签名 (2) 编写单元测试（正常+边界+异常） (3) 运行测试确认红灯 (4) 编写实现 (5) 运行测试确认绿灯。**每个 task 都必须独立执行此流程，不能攒到最后统一补测试**
- **适用场景**：所有 Service 层代码变更，必须无例外执行。多 task 实施时尤其要注意，每个 task 完成时必须有对应测试

## 2026-04-08 Service 拆接口后旧测试编译失败

- **现象**：将 Service 从 class 拆为 interface + impl 后，旧测试中 `new DeployService(...)` 编译失败（接口无法实例化）
- **原因**：重构时只关注了主代码，没有同步检查测试代码
- **正确做法**：拆接口时必须同步修改测试：(1) `new XxxService()` → `new XxxServiceImpl()` (2) 包私有成员的测试移到 impl 包下
- **适用场景**：任何涉及 class → interface 的重构

## 2026-04-08 @Async 方法在同 bean 内调用不走代理

- **现象**：原计划在 ProjectServiceImpl.createProject 末尾调用 GitService.cloneRepository，但如果 GitService 反过来依赖 ProjectService 会造成循环依赖
- **原因**：Spring `@Async` 依赖 AOP 代理，同 bean 内调用不经过代理，异步不生效；且相互依赖会导致循环注入
- **正确做法**：`@Async` 方法放在独立 Service 中，由 Controller 层编排调用顺序（先 createProject 再 cloneRepository），避免 Service 间循环依赖
- **适用场景**：涉及 `@Async`/`@Transactional` 等需要 AOP 代理的场景

## 2026-04-09 先写业务代码再补测试（第四次：local 项目 workspacePath 赋值逻辑变更）

- **现象**：修改 `ProjectServiceImpl.createProject()` 中 local 类型的 `workspacePath` 赋值逻辑后，才补充单元测试断言 `workspacePath == localPath`
- **原因**：AI 将"先改业务逻辑、再补测试"视为默认流程，在小改动场景尤其容易犯
- **正确做法**：无论改动大小，必须先写/改测试（定义预期：local 项目 workspacePath 应等于 localPath），确认红灯后再改业务实现，确认绿灯。**这是不可跳过的顺序约束**
- **适用场景**：所有 Service 层代码变更，包括"看起来很小"的字段赋值逻辑修改

## 2026-04-08 @Async 同 bean 内自调用仍不走代理（第二次验证）

- **现象**：GitServiceImpl.retryClone() 内部调用 this.cloneRepository()，虽然 cloneRepository 有 @Async 注解，但实际同步执行，WebSocket 进度推送不工作
- **原因**：即使 @Async 方法在独立 bean 中，**同一 bean 内的 this 调用仍绕过 Spring AOP 代理**。这是 Spring 代理机制的根本限制，不仅限于跨 bean 场景
- **正确做法**：需要在同一 bean 内调用 @Async 方法时，注入自身代理：`@Lazy GitService self`，然后通过 `self.cloneRepository()` 调用。@Lazy 避免循环依赖
- **适用场景**：任何 bean 中一个普通方法需要调用同 bean 的 @Async/@Transactional 方法时
