# 方案设计：规范中心 (Spec Center)

## 实现思路

以 Skill 模块为参考蓝本，新建独立的 `dev_spec`（规范主表）和 `spec_project_link`（关联表）。
关联操作：文件写入先于 DB（延续已有规范），CLAUDE.md 注入幂等。

---

## 数据库

### V12：dev_spec 表

```sql
CREATE TABLE dev_spec (
    id         BIGINT       NOT NULL COMMENT '主键（雪花算法）',
    spec_id    VARCHAR(64)  NOT NULL COMMENT '业务主键',
    name       VARCHAR(100) NOT NULL COMMENT '规范名称',
    spec_type  VARCHAR(20)  NOT NULL COMMENT '规范类型：UI/FRONTEND/BACKEND',
    content    MEDIUMTEXT   NULL     COMMENT '规范内容（Markdown 格式）',
    created_by VARCHAR(64)  NULL     COMMENT '创建人',
    created_at DATETIME     NOT NULL COMMENT '创建时间',
    updated_at DATETIME     NOT NULL COMMENT '更新时间',
    deleted    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_spec_id (spec_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='开发规范表';
```

### V13：spec_project_link 表

```sql
CREATE TABLE spec_project_link (
    id         BIGINT      NOT NULL COMMENT '主键（雪花算法）',
    spec_id    VARCHAR(64) NOT NULL COMMENT '规范业务主键',
    project_id VARCHAR(64) NOT NULL COMMENT '项目业务主键',
    created_at DATETIME    NOT NULL COMMENT '关联时间',
    updated_at DATETIME    NOT NULL COMMENT '更新时间',
    deleted    TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_spec_project (spec_id, project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规范-项目关联表';
```

---

## 后端文件清单

### 新建文件

| 文件 | 说明 |
|------|------|
| `platform-web/src/main/resources/db/migration/V12__add_dev_spec.sql` | dev_spec 表 |
| `platform-web/src/main/resources/db/migration/V13__add_spec_project_link.sql` | 关联表 |
| `platform-domain/.../entity/DevSpec.java` | 规范实体 |
| `platform-domain/.../entity/SpecProjectLink.java` | 关联实体（仅 id/specId/projectId，无 createdBy/deleted） |
| `platform-domain/.../mapper/DevSpecMapper.java` | 继承 BaseMapper |
| `platform-domain/.../mapper/SpecProjectLinkMapper.java` | 继承 BaseMapper |
| `platform-service/.../service/spec/SpecService.java` | 服务接口 |
| `platform-service/.../service/spec/impl/SpecServiceImpl.java` | 服务实现 |
| `platform-service/.../service/spec/SpecProjectLinkMapper.java` | 关联 Mapper（已在 domain 层） |
| `platform-web/.../controller/SpecController.java` | REST 控制器 |
| `platform-web/.../dto/CreateSpecRequest.java` | 创建请求 DTO |
| `platform-web/.../dto/UpdateSpecRequest.java` | 更新请求 DTO |
| `platform-web/.../dto/LinkSpecRequest.java` | 关联请求 DTO |
| `platform-web/.../dto/SpecVO.java` | 响应 VO |
| `platform-service/.../test/.../SpecServiceImplTest.java` | 单元测试 |

---

## API 设计

```
GET    /api/specs                          查询规范列表
POST   /api/specs                          创建规范
PUT    /api/specs/{specId}                 更新规范
DELETE /api/specs/{specId}                 删除规范（有关联时拒绝或级联）
GET    /api/specs/{specId}/links           查询该规范已关联的项目列表
POST   /api/specs/{specId}/link            关联到项目
DELETE /api/specs/{specId}/link/{projectId} 解除关联
```

---

## 关键逻辑：linkToProject

```
1. 查找 devSpec（不存在则 BusinessException）
2. 查找 project（不存在则 BusinessException）
3. 校验 project.codePath 非空（否则 BusinessException："项目未初始化，codePath 为空"）
4. 查询 spec_project_link 是否已存在（幂等：存在则直接返回）
5. 写文件：{codePath}/rules/{specId}.md  ← 先于 DB
6. 追加 CLAUDE.md：{codePath}/CLAUDE.md（不存在则创建）
   - 检查文件中是否已有 "rules/{specId}.md" 引用（幂等）
   - 若无，在文件末尾追加规范引用块
7. 插入 spec_project_link 记录
8. 日志：INFO specId/projectId
```

**CLAUDE.md 注入内容**：

```markdown

## 开发规范（自动注入，请勿手动删除）

在进行 UI 设计、前端开发、后端开发过程中，必须强制遵循以下规范文件中的内容：
- rules/{specId}.md：{specName}（类型：{specType}）
```

---

## 关键逻辑：unlinkFromProject

```
1. 查找 specProjectLink（不存在则静默返回）
2. 删除磁盘文件 {codePath}/rules/{specId}.md（不存在则静默忽略）
3. 从 CLAUDE.md 中移除对应引用行（未找到则静默忽略）
4. 软删除 spec_project_link 记录
```

---

## 关键逻辑：deleteSpec

- 若存在未删除的关联记录 → 抛 BusinessException（提示先解除关联）
- 否则软删除

---

## 前端文件清单

### 新建文件

| 文件 | 说明 |
|------|------|
| `platform-frontend/src/api/spec.js` | 规范 API 封装 |
| `platform-frontend/src/views/SpecView.vue` | 规范管理面板（参考 SkillView.vue 结构） |

### 修改文件

| 文件 | 修改点 |
|------|--------|
| `platform-frontend/src/views/WorkbenchView.vue` | 新增 `ruleMode` ref；新增「规范」入口；所有模式切换处重置 `ruleMode` |

---

## 前端 WorkbenchView 修改要点

### 新增 ref
```js
const ruleMode = ref(false)
```

### 侧边栏顺序（新任务 → 规范 → 技能和应用）
```html
<!-- 新任务 -->
<div class="wb-action-item" @click="handleNewTask">...</div>

<!-- 规范（新增） -->
<div class="wb-action-item" :class="{ active: ruleMode }" @click="handleRules">
  <!-- 书本/规则 SVG 图标 -->
  <span>规范</span>
</div>

<!-- 技能和应用 -->
<div class="wb-action-item" :class="{ active: skillMode }" @click="handleSkillAndApps">...</div>
```

### 模式互斥
所有模式切换函数（`handleNewTask`、`handleSkillAndApps`、`handleRules`、`selectThread`、`handleNewThread`）中均重置其他 mode 为 false。

### 主内容区
```html
<template v-if="skillMode">
  <SkillView />
</template>
<template v-else-if="ruleMode">
  <SpecView />
</template>
<template v-else>
  <!-- 原有新任务/聊天逻辑 -->
</template>
```

---

## 测试范围（TDD）

先写测试再写实现：

| 测试方法 | 场景 |
|----------|------|
| `should_createSpec_successfully` | 正常创建 |
| `should_updateSpec_successfully` | 正常更新 |
| `should_throwException_when_deleteSpec_with_existing_links` | 有关联时删除拒绝 |
| `should_linkToProject_successfully` | 正常关联，验证文件写入 + CLAUDE.md 追加 |
| `should_throwException_when_codePath_is_empty` | codePath 为空时关联失败 |
| `should_linkToProject_idempotent` | 重复关联幂等 |
| `should_unlinkFromProject_successfully` | 正常解除关联 |

---

## 关键决策

1. **关联表不复用 BaseEntity**：`spec_project_link` 是纯关联记录，无需 `createdBy`/`deleted`（若 soft delete 够简单则考虑保留 deleted 字段）。经权衡，**保留 `deleted` 和时间字段，去掉 `createdBy`**，继承 BaseEntity 但忽略 createdBy。
   - 实际方案：不继承 BaseEntity，自定义 4 个字段（id/specId/projectId/createdAt/updatedAt/deleted），MyBatis-Plus `@TableField(fill=...)` 手动标注。

2. **specId 作为文件名**：文件名使用 `specId`（短 UUID），不使用中文名称，避免文件系统编码问题。

3. **CLAUDE.md 注入区域识别**：通过检查文件中是否含有字符串 `rules/{specId}.md` 来判断幂等，不解析结构。
