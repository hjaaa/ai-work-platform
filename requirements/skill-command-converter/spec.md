# Feature Specification: 工作台 $skill-name 命令转换

**Created**: 2026-04-08
**Status**: Approved

## 背景

CC CLI 的 `-p` 模式是非交互式的，不经过交互式命令解析器，因此 `/skill-name` 这类 slash command 不会被识别。需要在 Java 层拦截用户输入的 skill 命令，转换为自然语言 prompt，使 CC CLI 的 agent 能主动调用 Skill 工具。

## 用户场景

### User Story 1 - 用户在对话框输入 skill 命令 (Priority: P1)

用户在工作台对话框输入 `$req-dev 实现用户登录功能`，系统自动将其转换为自然语言 prompt 发送给 CC CLI，CC CLI 的 agent 识别后调用 Skill 工具执行 req-dev skill。

**Acceptance Scenarios**:

1. **Given** 用户输入 `$req-dev 实现登录`, **When** 消息发送到后端, **Then** CC CLI 收到的 prompt 为自然语言指令（包含 skill 名称和参数），而非原始的 `$req-dev` 格式
2. **Given** 用户输入普通消息（不以 `$` 开头）, **When** 消息发送到后端, **Then** 消息原样传递给 CC CLI，不做任何转换
3. **Given** 用户输入 `$req-dev`（无参数）, **When** 消息发送到后端, **Then** 仍正确转换，skill 参数为空

### User Story 2 - 保留原始消息用于展示 (Priority: P1)

用户的历史对话记录中显示的是原始输入（如 `$req-dev 实现登录`），而不是转换后的自然语言 prompt。

**Acceptance Scenarios**:

1. **Given** 用户输入 `$req-dev 实现登录`, **When** 查看历史对话, **Then** 用户消息显示为 `$req-dev 实现登录`

## 功能需求

- **FR-001**: 系统 MUST 识别以 `$` 开头的 skill 命令格式（`$skill-name args`）
- **FR-002**: 系统 MUST 将 skill 命令转换为自然语言 prompt，包含 skill 名称和参数
- **FR-003**: 系统 MUST 保留原始用户消息用于持久化和展示
- **FR-004**: 系统 MUST 支持带命名空间的 skill 名称（如 `$superpowers:brainstorming`）
- **FR-005**: 非 skill 消息 MUST 原样透传，不做任何处理

## 设计决策

- 使用 `$` 而非 `/` 作为前缀，避免与 CC CLI 内部 slash command 语义冲突
- 转换逻辑放在 Service 层（`SkillPromptConverter`），不侵入 Orchestrator
- 只转换发给 CC CLI 的 prompt，用户消息存储保持原样
