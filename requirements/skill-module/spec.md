# 工作台技能和应用模块

## 背景

工作台侧边栏已有"技能和应用"按钮，但未实现功能。需要开发完整的 Skill 管理模块，支持平台用户在界面上对 Claude Code skill 进行 CRUD，并将变更同步写入对应磁盘目录的 `.md` 文件。

## 需求描述

### 核心功能
1. 点击侧边栏"技能和应用"后，主内容区展示 Skill 列表
2. 支持新增 Skill（表单填写）
3. 支持修改 Skill（内容变更同步更新磁盘文件）
4. 支持删除 Skill（DB 软删除 + 磁盘文件物理删除）

### Skill 作用域区分

| scope | 含义 | 磁盘路径 |
|-------|------|----------|
| `PERSONAL` | 个人级，所有项目可用 | `~/.claude/skills/{skillId}.md` |
| `PROJECT` | 项目级，仅关联项目可用 | `{project.codePath}/.claude/skills/{skillId}.md` |

### 业务约束
- `scope=PROJECT` 时，必须关联一个项目（projectId 不为空）
- 关联项目的 `codePath` 必须非空，否则禁止创建项目级 Skill
  - local 类型项目：`codePath` 与 `localPath` 保持一致（数据写入时由后端保证）
  - git 类型项目：`codePath` 为 clone 后的目录，未 clone 则为空
- 新增/修改 Skill 时，自动在对应磁盘路径创建或覆盖 `.md` 文件
- 删除 Skill 时：DB 软删除（`deleted=1`）+ 磁盘文件物理删除（文件不存在时静默忽略）

## 数据模型

### skill 表（新建）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键（雪花算法） |
| skill_id | VARCHAR(64) | 业务主键（全局唯一） |
| name | VARCHAR(100) | Skill 名称 |
| description | TEXT | Skill 描述（markdown 内容，写入文件） |
| scope | VARCHAR(20) | 范围：PERSONAL / PROJECT |
| project_id | VARCHAR(64) | 关联项目 ID（scope=PROJECT 时必填） |
| created_by | VARCHAR(64) | 创建人 |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |
| deleted | TINYINT | 逻辑删除：0-未删除，1-已删除 |

### 枚举
- `SkillScope`: `PERSONAL`, `PROJECT`

## 接口设计

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/skills?scope=&projectId=` | 查询 Skill 列表（按 scope 过滤） |
| POST | `/api/skills` | 创建 Skill |
| PUT | `/api/skills/{skillId}` | 修改 Skill |
| DELETE | `/api/skills/{skillId}` | 删除 Skill |

## 前端功能

1. 点击"技能和应用"，主内容区切换为 Skill 列表视图
2. Skill 列表展示：名称 + 描述摘要 + scope 标签 + 操作按钮（编辑、删除）
3. 新建/编辑 Skill：弹窗表单（名称、描述、scope 选择、scope=PROJECT 时选项目）
4. 删除前弹二次确认

## 非功能需求

- 遵循现有分层规范（Controller/Service/Mapper）
- Service 拆接口+实现类
- 前端样式遵循 `platform-frontend/DESIGN.md`
- 单元测试覆盖 Service 核心方法（正常/边界/异常）
