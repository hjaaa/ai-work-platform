# 实施方案：工作台技能和应用模块

## 关键决策

1. **文件操作放在 Service 层**：写/删文件与 DB 操作在同一 Service 方法中，文件操作失败抛 BusinessException 回滚（DB 事务保护）
2. **个人 skill 路径扩展符处理**：`~/.claude/skills/` 使用 `System.getProperty("user.home")` 解析，不依赖 shell
3. **skillId 生成规则**：UUID 去横线（8位短串），全局唯一
4. **local 项目 codePath 同步**：创建 local 类型项目时 codePath 与 localPath 保持一致（由 ProjectService 保证，本需求只读不写 Project）
5. **删除策略**：DB `@TableLogic` 软删除 + 文件物理删除，文件不存在时静默跳过
6. **SkillScope 枚举**：放在 `platform-domain` 模块

## 任务拆解

### Task 1 — 数据库迁移（Flyway V11）
- 新建 `V11__add_skill_table.sql`
- 建 `skill` 表，所有列带 `COMMENT`

### Task 2 — Domain 层
- 新增 `SkillScope` 枚举（PERSONAL / PROJECT）
- 新增 `Skill` 实体（继承 BaseEntity，字段含中文注释，`@EqualsAndHashCode(callSuper=true)`）

### Task 3 — Mapper 层
- 新增 `SkillMapper` 接口（继承 `BaseMapper<Skill>`）
- 新增 `SkillMapper.xml`（暂无复杂查询，保留空壳）

### Task 4 — Service 层（先写测试）
- `SkillService` 接口：`list / create / update / delete`
- `SkillServiceImpl` 实现：
  - `create`：校验 scope=PROJECT 时 projectId + codePath 非空 → 写文件 → 插入 DB
  - `update`：查已有 Skill → 更新 DB → 覆盖文件
  - `delete`：软删除 DB → 物理删除文件
  - `list`：按 scope / projectId 过滤，MyBatis-Plus 条件构造
- 单元测试：`SkillServiceImplTest`（Mockito mock SkillMapper + ProjectMapper）

### Task 5 — Web 层
- DTO：`CreateSkillRequest` / `UpdateSkillRequest` / `SkillVO`（含 javax validation）
- `SkillController`：路由 `/api/skills`，构造器注入 `SkillService`
- 统一 `Result<T>` 返回

### Task 6 — 前端
- `platform-frontend/src/api/skill.js`：封装 5 个接口
- `SkillView.vue`：Skill 列表展示（名称 + 描述摘要 + scope 标签 + 编辑/删除）
- `WorkbenchView.vue`：点击"技能和应用"切换到 SkillView 视图
- 新建/编辑弹窗：scope=PROJECT 时显示项目下拉选择（调现有 project 列表接口）

## 实施顺序

```
Task 1 (DB) → Task 2 (Domain) → Task 3 (Mapper) → Task 4 (Service + 测试) → Task 5 (Web) → Task 6 (前端)
```

## 风险点

| 风险 | 处理方式 |
|------|----------|
| 文件写入失败但 DB 已提交 | 文件操作在 DB 操作前执行；文件失败直接抛异常不进入 DB 操作 |
| 删除时文件不存在 | `Files.deleteIfExists()` 静默忽略 |
| local 项目 codePath 为空 | 创建 PROJECT skill 时前置校验，返回明确错误信息 |
| `~` 路径解析 | `Paths.get(System.getProperty("user.home"), ".claude", "skills")` |
