# Feature Specification: 支持本地项目类别

**Created**: 2026-04-09
**Status**: Draft
**Input**: 当前项目都是 git 拉取的，希望增加本地项目类别，复用 workspacePath 字段作为本地文件路径

## User Story 1 - 创建本地项目 (Priority: P1)

用户在创建项目时可以选择"本地项目"类型，填写本地文件路径（而非 Git URL），系统验证路径有效后直接创建项目，状态为 active，无需 clone 流程。

**Acceptance Scenarios**:

1. **Given** 用户在创建项目表单, **When** 选择"本地项目"并填写有效本地路径, **Then** 项目创建成功，状态直接为 active，workspacePath 为用户填写的路径
2. **Given** 用户选择"本地项目", **When** 填写的路径不存在或不可读, **Then** 提示路径无效，拒绝创建
3. **Given** 用户选择"Git 项目"（默认）, **When** 按现有流程填写 gitUrl, **Then** 行为与当前完全一致，无回归

## User Story 2 - 本地项目在列表和详情中正确展示 (Priority: P1)

本地项目在项目列表中显示"本地"标签，详情页展示本地路径而非 Git 信息。

**Acceptance Scenarios**:

1. **Given** 项目列表中有本地项目, **When** 查看列表, **Then** 本地项目显示"本地"标签，不显示 clone 状态
2. **Given** 打开本地项目详情, **When** 查看详情, **Then** 显示本地路径，不显示 Git URL/分支/重试克隆按钮

## User Story 3 - 删除本地项目不清理源码目录 (Priority: P1)

删除本地项目时，只删除数据库记录，不删除用户本地的源码目录（与 Git 项目行为不同）。

**Acceptance Scenarios**:

1. **Given** 存在一个本地项目, **When** 删除该项目, **Then** 数据库记录逻辑删除，本地文件目录保持不变

### Edge Cases

- 本地路径指向的目录被外部删除后，项目详情如何展示？→ 正常展示，不阻塞
- 同一个路径能否创建多个本地项目？→ 允许（不做唯一约束）

## Requirements

### Functional Requirements

- **FR-001**: 新增 `project_type` 字段，区分 `git`（默认）和 `local`
- **FR-002**: 本地项目创建时，`gitUrl` 非必填，`workspacePath` 由用户提供
- **FR-003**: 本地项目创建后状态直接为 `active`，跳过 clone 流程
- **FR-004**: 后端校验本地路径是否存在且为目录
- **FR-005**: 删除本地项目时不清理磁盘目录
- **FR-006**: 前端表单根据项目类型动态切换字段

### Key Entities

- **Project**: 新增 `projectType` 字段（git/local）
- **CreateProjectRequest**: 调整校验逻辑，按 projectType 分别验证

## Success Criteria

- **SC-001**: 可以成功创建本地项目并在列表中展示
- **SC-002**: Git 项目的创建/展示/删除行为完全不受影响
- **SC-003**: 删除本地项目不会删除用户磁盘文件

## Assumptions

- workspacePath 对于本地项目直接存储用户提供的路径，不再由系统生成
- 本地项目不需要 codePath 字段（无 clone 行为）
- 本地项目暂不支持部署功能
