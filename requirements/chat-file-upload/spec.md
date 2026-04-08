# Feature Specification: 工作台对话框文件上传

**Created**: 2026-04-08
**Status**: Approved

## 背景

CC CLI 可通过 Read 工具读取工作目录下的文件，但当前对话框只支持纯文本消息。
需要支持用户上传文件到项目工作目录，并在发送消息时告知 CC CLI 文件路径。

## 用户场景

### User Story 1 - 上传文件并发送消息 (Priority: P1)

用户在对话框选择/拖拽文件上传，文件存储到项目 `workspacePath/file/` 目录下。
发送消息时，prompt 中包含文件路径，CC CLI 通过 Read 工具读取文件内容。

**Acceptance Scenarios**:

1. **Given** 用户选择了一个文件, **When** 点击上传, **Then** 文件存储到 `workspacePath/file/` 下，返回文件相对路径
2. **Given** 用户已上传文件并输入消息, **When** 发送, **Then** CC CLI 收到的 prompt 包含文件路径提示
3. **Given** `workspacePath/file/` 目录不存在, **When** 上传文件, **Then** 自动创建目录

### User Story 2 - 文件名冲突处理 (Priority: P2)

上传同名文件时自动添加时间戳后缀，避免覆盖。

## 功能需求

- **FR-001**: 文件存储路径为 `workspacePath/file/`，由代码拼接，不加表字段
- **FR-002**: 目录不存在时自动创建
- **FR-003**: 同名文件添加时间戳后缀避免覆盖
- **FR-004**: 发送消息时如果附带文件，prompt 中拼接文件路径提示
- **FR-005**: 上传接口需校验文件大小（上限 10MB）和路径安全（不能逃逸出 file 目录）

## 设计决策

- 不新增 project 表字段，`filePath = workspacePath + "/file"` 直接拼接
- 文件上传和消息发送分两步：先上传获取路径，再发送消息时附带路径
