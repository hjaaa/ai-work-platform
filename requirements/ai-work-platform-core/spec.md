# Feature Specification: AI 工作平台

**Created**: 2026-04-07
**Status**: Draft

## 概述

面向**完全不懂代码的产品经理**的 AI 工作平台。产品经理通过上传 PRD（Word/在线文档）或自然语言对话描述需求，平台借助 Claude Code CLI 自动完成代码生成、测试、部署的全流程。

**功能边界**：CRUD 页面、表单配置、前端样式修改。不涉及复杂业务逻辑、算法、或基础架构变更。

**技术栈**：Java Spring Boot（后端）+ Vue（前端）+ Claude Code CLI（AI 引擎）+ Docker（部署）

---

## User Scenarios & Testing

### User Story 1 — 自然语言对话式开发 (Priority: P1)

产品经理在平台 Web 界面中用自然语言描述需求（如"我要一个用户管理页面，能增删改查"），AI 理解意图后生成代码，产品经理预览效果、确认后一键部署。

**Why this priority**: 这是平台的核心价值链路——从"说需求"到"看到效果"的最短闭环。

**Independent Test**: 产品经理输入一句话描述 → 看到生成的页面预览 → 确认部署 → 在测试环境访问到页面。

**Acceptance Scenarios**:

1. **Given** 产品经理登录平台, **When** 输入"创建一个员工信息管理页面，包含姓名、工号、部门、手机号字段", **Then** AI 生成前后端代码并展示页面预览
2. **Given** AI 已生成代码预览, **When** 产品经理点击"确认部署", **Then** 系统自动执行测试、构建 Docker 镜像、部署到测试环境，并返回可访问链接
3. **Given** 产品经理对预览效果不满意, **When** 输入修改意见（如"把表格换成卡片样式"）, **Then** AI 基于上下文增量修改并重新预览

---

### User Story 2 — 自然语言生成 PRD (Priority: P2)

产品经理用自然语言描述想法（如"我想做一个请假审批流程"），AI 通过多轮对话追问细节，最终生成结构化的 PRD 文档（Word 格式），供下载、评审或直接流转到代码生成阶段。

**Why this priority**: 产品经理经常有想法但不知道如何写成规范的 PRD，AI 辅助生成 PRD 降低了文档编写门槛，也让后续的代码生成有更高质量的输入。

**Independent Test**: 产品经理输入一段需求描述 → AI 追问细节 → 生成 PRD Word 文档 → 下载查看 / 一键续接开发。

**Acceptance Scenarios**:

1. **Given** 产品经理选择"生成 PRD"模式, **When** 输入"我想做一个员工请假管理功能", **Then** AI 主动追问（审批流程？假期类型？权限？）并逐步生成结构化 PRD
2. **Given** AI 完成 PRD 生成, **When** 产品经理点击"下载", **Then** 系统输出格式规范的 Word 文档（含标题、背景、用户故事、功能点、验收标准）
3. **Given** PRD 已生成, **When** 产品经理点击"基于此 PRD 开发", **Then** 系统自动将 PRD 内容作为输入，流转到代码生成流程（User Story 1）
4. **Given** 多轮对话中产品经理修改了之前确认的内容, **When** AI 检测到变更, **Then** 更新 PRD 对应章节而非重新生成整份文档

---

### User Story 3 — PRD 文档解析开发 (Priority: P2)

产品经理上传 Word 格式的 PRD 或粘贴在线文档链接，平台解析文档内容，提取功能需求，逐项生成代码。

**Why this priority**: 产品经理的日常产出就是 PRD，支持文档输入降低使用门槛。

**Independent Test**: 上传一份包含 CRUD 描述的 Word 文档 → 平台展示解析出的功能列表 → 产品经理确认 → 逐项生成代码。

**Acceptance Scenarios**:

1. **Given** 产品经理上传一份 Word PRD, **When** 系统解析完成, **Then** 展示提取出的功能点列表，让产品经理确认/修改
2. **Given** 功能点列表已确认, **When** 产品经理点击"开始生成", **Then** AI 逐项生成代码，每完成一项展示进度和预览
3. **Given** PRD 中有描述不清的部分, **When** AI 无法确定意图, **Then** 通过对话向产品经理追问澄清

---

### User Story 4 — 自动测试与质量保障 (Priority: P2)

AI 生成代码的同时自动生成单元测试和基础集成测试，部署前必须测试通过。

**Why this priority**: 保证生成代码的质量，避免产品经理直接部署有 bug 的代码。

**Independent Test**: AI 生成 CRUD 代码 → 自动生成测试 → 运行测试 → 展示测试报告。

**Acceptance Scenarios**:

1. **Given** AI 完成代码生成, **When** 进入测试阶段, **Then** 自动生成并运行测试，展示测试通过率
2. **Given** 测试未全部通过, **When** AI 分析失败原因, **Then** 自动修复代码并重新测试（最多 3 轮）
3. **Given** 3 轮自动修复后仍有测试失败, **When** 系统判定无法自动修复, **Then** 通知开发人员介入，并展示失败详情

---

### User Story 5 — 一键部署到 Docker (Priority: P3)

代码和测试均通过后，一键构建 Docker 镜像并部署到指定服务器的容器中。

**Why this priority**: 部署是闭环的最后一步，但可以先用手动部署过渡。

**Independent Test**: 通过测试的代码 → 构建镜像 → 推送到目标服务器 → 启动容器 → 返回访问地址。

**Acceptance Scenarios**:

1. **Given** 代码测试全部通过, **When** 产品经理点击"部署", **Then** 系统构建 Docker 镜像、推送至目标服务器、启动容器
2. **Given** 部署完成, **When** 系统健康检查通过, **Then** 返回测试环境访问链接
3. **Given** 部署失败, **When** 系统检测到异常, **Then** 自动回滚到上一版本并通知用户

---

### User Story 6 — 多轮对话细化需求 (Priority: P3)

产品经理与 AI 进行多轮对话，逐步完善需求细节（字段类型、校验规则、页面布局等）。

**Why this priority**: 一句话描述往往不够精确，多轮对话能帮助 AI 生成更符合预期的结果。

**Acceptance Scenarios**:

1. **Given** 产品经理输入初始需求, **When** AI 判断信息不完整, **Then** 主动提问（如"需要哪些查询条件？""列表要分页吗？"）
2. **Given** 多轮对话进行中, **When** 产品经理修改之前确认过的字段, **Then** AI 更新已有生成结果而非重新生成

---

### Edge Cases

- PRD 文档格式损坏或无法解析时：提示用户检查文件格式，支持降级为手动输入
- 自然语言描述超出功能边界时（如"帮我写一个推荐算法"）：明确告知不支持并建议联系开发
- 部署目标服务器不可达时：提前检测连通性，失败时给出诊断信息
- 同时有多个产品经理操作同一项目时：通过项目隔离避免冲突

---

## Requirements

### Functional Requirements

- **FR-001**: 系统 MUST 提供 Web 界面的自然语言对话交互，产品经理输入需求描述后获得实时响应
- **FR-002**: 系统 MUST 支持上传 Word (.docx) 文件并解析其中的功能需求描述
- **FR-003**: 系统 MUST 支持粘贴在线文档链接并抓取解析内容
- **FR-004**: 系统 MUST 调用 Claude Code CLI 完成代码生成（Java Spring Boot 后端 + Vue 前端）
- **FR-005**: 系统 MUST 在代码生成后自动生成并执行单元测试和集成测试
- **FR-006**: 系统 MUST 支持一键构建 Docker 镜像并部署到指定服务器
- **FR-007**: 系统 MUST 提供实时的代码生成/测试/部署进度展示
- **FR-008**: 系统 MUST 在部署失败时支持自动回滚
- **FR-009**: 系统 MUST 保存每次生成的项目历史（代码、配置、部署记录）
- **FR-010**: 系统 MUST 在 AI 无法理解需求时主动追问，而非猜测生成
- **FR-011**: 系统 MUST 支持"生成 PRD"模式，通过多轮对话将自然语言描述转化为结构化 PRD 文档
- **FR-012**: 系统 MUST 支持将生成的 PRD 导出为 Word (.docx) 格式下载
- **FR-013**: 系统 MUST 支持将生成的 PRD 一键流转到代码生成流程

### Non-Functional Requirements

- **NFR-001**: 代码生成响应时间 ≤ 3 分钟（单个 CRUD 模块）
- **NFR-002**: 部署流程 ≤ 5 分钟（从点击部署到可访问）
- **NFR-003**: 平台并发支持 ≥ 5 个产品经理同时使用

### Key Entities

- **Project（项目）**: 产品经理创建的工作单元，包含需求描述、生成的代码、部署信息
- **Requirement（需求）**: 从自然语言/PRD 解析出的功能点，关联到 Project
- **Generation（生成记录）**: 一次代码或 PRD 生成的完整记录（类型：code/prd，输入、输出、测试结果）
- **Deployment（部署记录）**: Docker 镜像构建和部署的记录（镜像版本、目标服务器、状态）
- **Conversation（对话）**: 产品经理与 AI 的交互历史，关联到 Project

---

## Success Criteria

- **SC-001**: 产品经理无需任何技术培训，5 分钟内完成从需求描述到页面预览的全流程
- **SC-002**: 生成的 CRUD 页面代码测试通过率 ≥ 90%
- **SC-003**: 一键部署成功率 ≥ 95%
- **SC-004**: 产品经理使用满意度 ≥ 80%（通过后续调研获取）

---

## Assumptions

- 目标服务器已安装 Docker 并配置好 SSH 免密登录
- Claude Code CLI 已在服务器上部署并可用，有足够的 API 额度
- 生成的项目基于统一的 Spring Boot + Vue 脚手架模板
- 第一版仅支持内部测试环境部署，不涉及生产环境
- 产品经理使用浏览器访问平台（支持 Chrome/Edge）
- 每个项目独立隔离（独立 Git 仓库、独立 Docker 容器）
