# Implementation Plan: AI 工作平台

**Date**: 2026-04-07 | **Spec**: `requirements/ai-work-platform-core/spec.md`

## Summary

构建一个 Web 平台，让产品经理通过自然语言或 PRD 文档描述需求，平台调用 Claude Code CLI 自动生成 Spring Boot + Vue 代码，自动测试，一键部署到 Docker 容器。

## Technical Context

**Language/Version**: Java 21+ (Spring Boot 3.5.x), Node.js 18+ (Vue 3)
**Primary Dependencies**: Spring Boot 3.5.x, Vue 3 + Vite, WebSocket (STOMP), Apache POI (docx 解析)
**Storage**: MySQL 8 (项目/需求/部署记录), Redis (会话缓存/任务状态)
**Testing**: JUnit 5 + Mockito (后端), Vitest (前端)
**Target Platform**: Linux Server (Docker)
**Project Type**: Web Application (平台本身 + 生成的目标项目)

---

## 整体架构

```
┌─────────────────────────────────────────────────────┐
│                   产品经理浏览器                       │
│              Vue 3 SPA (Chat UI)                     │
└───────────────┬─────────────────────────────────────┘
                │ HTTP / WebSocket (STOMP)
┌───────────────▼─────────────────────────────────────┐
│              Platform Backend (Spring Boot)           │
│                                                      │
│  ┌──────────┐ ┌──────────┐ ┌───────────┐ ┌────────┐ │
│  │ Chat API │ │ Doc API  │ │Project API│ │PRD API │ │
│  │(WebSocket)│ │(REST)    │ │ (REST)    │ │(REST)  │ │
│  └────┬─────┘ └────┬─────┘ └─────┬─────┘            │
│       │             │             │                   │
│  ┌────▼─────────────▼─────────────▼─────┐            │
│  │         RequirementService            │            │
│  │  (需求解析 + 意图理解 + 对话管理)       │            │
│  └──────────────┬───────────────────────┘            │
│                 │                                     │
│  ┌──────────────▼───────────────────────┐            │
│  │      Claude Code Orchestrator         │            │
│  │  (CLI 进程管理 + 流式输出 + 超时控制)   │            │
│  └──────────────┬───────────────────────┘            │
│                 │                                     │
│  ┌──────────────▼──────┐  ┌─────────────┐            │
│  │   TestRunner Service │  │  Deployer   │            │
│  │  (执行测试+结果解析)   │  │(Docker+SSH) │            │
│  └─────────────────────┘  └─────────────┘            │
└──────────────────────────────────────────────────────┘
                │
    ┌───────────▼───────────┐
    │  Generated Projects    │
    │  /workspace/projects/  │
    │  ├── proj-001/         │
    │  │   ├── backend/      │  ← Spring Boot 脚手架
    │  │   ├── frontend/     │  ← Vue 脚手架
    │  │   └── docker-compose.yml
    │  └── proj-002/
    └───────────────────────┘
```

---

## 核心模块设计

### 模块 1: Platform Backend（平台后端）

Spring Boot 多模块 Maven 项目：

```
ai-work-platform/
├── pom.xml                          # 父 POM
├── platform-common/                 # 通用工具、异常、常量
│   └── src/main/java/
├── platform-domain/                 # 实体、DTO、VO、枚举
│   └── src/main/java/
├── platform-service/                # 核心业务逻辑
│   └── src/main/java/
│       ├── chat/                    # 对话管理
│       ├── document/                # 文档服务（PRD 生成 + 文档解析）
│       ├── orchestrator/            # Claude Code 编排
│       ├── project/                 # 项目生命周期
│       ├── test/                    # 测试执行
│       └── deploy/                  # Docker 部署
├── platform-web/                    # Controller + WebSocket + 配置
│   └── src/main/java/
└── platform-frontend/               # Vue 3 前端
    └── src/
```

### 模块 2: Claude Code Orchestrator（核心引擎）

这是平台最关键的模块——管理 Claude Code CLI 进程。

**设计要点**：
- 通过 `ProcessBuilder` 启动 Claude Code CLI 进程
- 使用 `--print` 或 `-p` 模式执行非交互式命令
- 流式读取 stdout/stderr，通过 WebSocket 推送给前端
- 超时控制（单次生成 ≤ 5 分钟）
- 进程池管理，限制并发数（避免资源耗尽）

**调用流程**：
```
1. 准备项目工作区（从脚手架模板 clone）
2. 构建 prompt（需求描述 + 项目上下文 + 编码规范）
3. 启动 Claude Code CLI 进程
   claude -p "根据以下需求生成代码: {prompt}" --workdir {project_path}
4. 流式读取输出 → WebSocket 推送前端
5. 完成后解析生成的文件列表
6. 触发测试阶段
```

**Prompt 模板策略**：
- 维护一套 prompt 模板（CRUD/表单/样式修改等场景）
- 模板中注入：脚手架结构说明 + 编码规范 + 已有代码上下文
- 多轮对话通过 `--resume` 或 session 续接

### 模块 3: Document Service（文档服务）

承担两个方向的文档处理能力：

**3a. PRD Generator（PRD 生成）— 新增**
- 产品经理选择"生成 PRD"模式后进入此流程
- 调用 Claude Code Orchestrator，使用专用的 PRD 生成 prompt 模板
- prompt 模板要求 AI 输出结构化内容：背景、用户故事、功能点、验收标准、非功能需求
- 支持多轮对话：AI 主动追问模糊点（审批流？角色权限？字段约束？）
- 对话结束后，通过 Apache POI 将结构化内容渲染为 Word 文档
- 提供"基于此 PRD 开发"按钮，将 PRD 内容注入代码生成流程的 prompt

**3b. Document Parser（文档解析）— 已有**
- Word (.docx): Apache POI 读取，提取文本 + 表格 + 标题结构
- 在线文档链接: 使用 Jsoup 抓取或调用文档平台 API（需适配不同平台）
- 解析后输出结构化的功能点列表（JSON），交给 Claude Code 逐项处理

**两者共用**：Apache POI 依赖、PRD 结构化模型（PrdDocument）、Claude Code Orchestrator

### 模块 4: Project Manager（项目管理）

- 每个需求创建独立项目目录 `/workspace/projects/{project-id}/`
- 从**脚手架模板仓库** clone 初始化（预配置好的 Spring Boot + Vue 项目）
- Git 版本管理（每次生成/修改自动 commit）
- 项目元数据持久化到 MySQL

### 模块 5: Test Runner（测试执行）

- 后端测试: 在项目目录执行 `mvn test`，解析 surefire 报告
- 前端测试: 执行 `npm run test`，解析测试输出
- 测试失败时：将失败信息反馈给 Claude Code，要求修复（最多 3 轮）
- 通过 WebSocket 实时推送测试进度

### 模块 6: Deployer（部署服务）

```
1. mvn package -DskipTests     # 后端打包
2. npm run build                # 前端构建
3. docker build                 # 构建镜像（Dockerfile 在脚手架中预置）
4. docker push / scp            # 推送到目标服务器
5. docker-compose up -d         # 启动容器
6. 健康检查（HTTP ping）
7. 失败则 docker-compose down + 回滚到上一镜像版本
```

部署目标服务器信息通过平台配置管理（IP、SSH key、Docker registry）。

### 模块 7: Platform Frontend（平台前端）

```
platform-frontend/
├── src/
│   ├── views/
│   │   ├── ChatView.vue         # 主对话界面（核心页面，含模式选择）
│   │   ├── ProjectListView.vue  # 项目列表
│   │   ├── ProjectDetailView.vue# 项目详情（代码/测试/部署）
│   │   └── SettingsView.vue     # 服务器配置
│   ├── components/
│   │   ├── ChatInput.vue        # 输入框 + 文件上传
│   │   ├── ChatMessage.vue      # 消息气泡（支持 Markdown）
│   │   ├── ModeSelector.vue     # 模式选择（直接开发 / 先生成 PRD）
│   │   ├── PrdPreview.vue       # PRD 预览 + 下载 + 流转按钮
│   │   ├── CodePreview.vue      # 代码预览（语法高亮）
│   │   ├── DeployProgress.vue   # 部署进度条
│   │   └── TestReport.vue       # 测试报告展示
│   ├── stores/                  # Pinia 状态管理
│   ├── api/                     # HTTP + WebSocket 客户端
│   └── router/
```

---

## 脚手架模板（预先准备）

需要预先准备一个标准化的 Spring Boot + Vue 项目模板：

```
scaffold-template/
├── backend/
│   ├── pom.xml                  # 预配置依赖（MyBatis-Plus, Validation 等）
│   ├── src/main/java/.../
│   │   ├── config/              # 通用配置（CORS, MyBatis, Swagger）
│   │   ├── common/              # 统一响应、异常处理、分页
│   │   └── module/              # Claude Code 在此目录下生成模块代码
│   ├── src/main/resources/
│   │   └── application.yml      # 预配置数据源占位符
│   └── Dockerfile
├── frontend/
│   ├── package.json             # 预配置依赖（Element Plus, Axios 等）
│   ├── src/
│   │   ├── components/          # 通用组件
│   │   ├── views/               # Claude Code 在此目录下生成页面
│   │   └── router/
│   ├── Dockerfile
│   └── nginx.conf
├── docker-compose.yml           # 前后端 + MySQL + Nginx
└── CLAUDE.md                    # 给 Claude Code 的项目约束文件
```

**关键设计**：脚手架中的 `CLAUDE.md` 文件约束 Claude Code 的行为：
- 在哪些目录下生成代码
- 使用哪些基类/工具类
- 编码风格要求
- 数据库连接信息

---

## 数据模型

```sql
-- 项目表
CREATE TABLE project (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id VARCHAR(64) UNIQUE NOT NULL COMMENT '项目标识',
    name VARCHAR(128) NOT NULL COMMENT '项目名称',
    description TEXT COMMENT '项目描述',
    workspace_path VARCHAR(512) NOT NULL COMMENT '工作区路径',
    status ENUM('creating','active','deploying','deployed','failed') DEFAULT 'creating',
    deploy_url VARCHAR(512) COMMENT '部署后的访问地址',
    created_by VARCHAR(64) NOT NULL COMMENT '创建人',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 对话表
CREATE TABLE conversation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id VARCHAR(64) NOT NULL,
    role ENUM('user','assistant','system') NOT NULL,
    content TEXT NOT NULL,
    message_type ENUM('text','file','code','progress') DEFAULT 'text',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_project_id (project_id)
);

-- 生成记录表
CREATE TABLE generation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id VARCHAR(64) NOT NULL,
    type ENUM('code','prd') DEFAULT 'code' COMMENT '生成类型：代码/PRD文档',
    prompt TEXT NOT NULL COMMENT '发送给 Claude Code 的完整 prompt',
    generated_files TEXT COMMENT '生成的文件列表 (JSON)',
    prd_content TEXT COMMENT 'PRD 生成时的结构化内容 (JSON)',
    status ENUM('running','success','failed') DEFAULT 'running',
    duration_ms INT COMMENT '耗时毫秒',
    error_message TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_project_id (project_id)
);

-- 部署记录表
CREATE TABLE deployment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id VARCHAR(64) NOT NULL,
    image_tag VARCHAR(128) NOT NULL COMMENT 'Docker 镜像标签',
    target_server VARCHAR(256) NOT NULL COMMENT '目标服务器',
    status ENUM('building','pushing','deploying','running','failed','rolled_back') DEFAULT 'building',
    deploy_url VARCHAR(512),
    error_message TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_project_id (project_id)
);
```

---

## 实施计划（分阶段）

### Phase 1: 基础骨架（P1 核心链路 MVP）

**目标**：产品经理输入一句话 → 生成代码 → 看到文件列表

| 任务 | 说明 | 预估 |
|------|------|------|
| 1.1 | 初始化 Maven 多模块项目 + Vue 项目 | 基础搭建 |
| 1.2 | 数据库建表 + MyBatis-Plus 基础 CRUD | 数据层 |
| 1.3 | WebSocket 通道搭建（STOMP） | 通信层 |
| 1.4 | Claude Code Orchestrator 核心逻辑 | 最关键模块 |
| 1.5 | Chat UI 基础界面 | 前端对话页 |
| 1.6 | 端到端联调：输入需求 → 调用 Claude Code → 返回结果 | 闭环验证 |

### Phase 2: 测试 + 预览

**目标**：生成代码后自动测试，支持代码预览

| 任务 | 说明 |
|------|------|
| 2.1 | TestRunner 服务（执行 mvn test / npm test） |
| 2.2 | 测试失败自动修复循环（最多 3 轮） |
| 2.3 | 代码预览组件（语法高亮） |
| 2.4 | 生成记录持久化 + 历史查看 |

### Phase 3: 文档服务（PRD 生成 + 文档解析）

**目标**：支持自然语言生成 PRD + Word 上传解析 + 在线文档链接

| 任务 | 说明 |
|------|------|
| 3.1 | PRD 生成 prompt 模板设计（结构化输出：背景/用户故事/功能点/验收标准） |
| 3.2 | PrdGenerateService（调用 Orchestrator + 多轮对话 + 结构化 PRD 输出） |
| 3.3 | PRD Word 导出（Apache POI 渲染结构化内容为 .docx） |
| 3.4 | PRD → 代码生成流转（一键将 PRD 内容注入代码生成 prompt） |
| 3.5 | 前端"生成 PRD"模式入口 + PRD 预览/下载/流转按钮 |
| 3.6 | Word 文档解析服务（Apache POI 解析已有 PRD） |
| 3.7 | 在线文档链接抓取（Jsoup） |
| 3.8 | 功能点提取 + 确认交互 |
| 3.9 | 批量生成（逐功能点调用 Claude Code） |

### Phase 4: Docker 部署

**目标**：一键构建 + 部署 + 健康检查

| 任务 | 说明 |
|------|------|
| 4.1 | Docker 镜像构建服务 |
| 4.2 | SSH 远程部署 + docker-compose |
| 4.3 | 健康检查 + 自动回滚 |
| 4.4 | 部署配置管理（服务器信息） |

### Phase 5: 体验优化

| 任务 | 说明 |
|------|------|
| 5.1 | 多轮对话上下文管理（Claude Code session） |
| 5.2 | 项目列表 + 管理页面 |
| 5.3 | 实时进度展示优化 |
| 5.4 | 错误处理 + 边界场景 |

---

## 关键决策记录

| 决策 | 选择 | 理由 |
|------|------|------|
| AI 引擎调用方式 | ProcessBuilder 启动 Claude Code CLI | 复用已部署的 CLI，无需额外 API 接入 |
| 前后端通信 | WebSocket (STOMP) | 代码生成/测试/部署都是长时间操作，需流式推送 |
| 项目隔离 | 独立目录 + 独立 Git | 简单可靠，避免项目间干扰 |
| 脚手架策略 | 预置模板 clone | 保证生成代码的一致性和可部署性 |
| 并发控制 | 进程池（Semaphore） | Claude Code CLI 是重资源操作，必须限制并发 |

---

## 风险与应对

| 风险 | 应对 |
|------|------|
| Claude Code CLI 生成质量不稳定 | 脚手架 + CLAUDE.md 约束 + prompt 模板标准化 |
| CLI 进程挂起/超时 | 强制超时（5 分钟）+ 进程强杀 + 错误提示 |
| 并发资源不足 | Semaphore 控制并发数 + 排队机制 |
| 生成的代码无法编译 | 自动测试 + 3 轮修复 + 人工兜底 |
| Docker 部署失败 | 健康检查 + 自动回滚到上一版本 |
