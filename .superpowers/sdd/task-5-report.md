# Task 5 报告：AppSidebar 侧边栏

## 实现内容

- 新增 `ai-work-ui/src/layout/AppSidebar.vue`。
- 按 brief 实现 `collapsed: boolean` props 控制的两态侧边栏：
  - 展开态宽度 `220px`，包含品牌区、首页入口、顶级菜单项、分组菜单项与折叠/展开交互。
  - 折叠态宽度 `64px`，渲染图标 rail，并为各项提供 `title`。
- 组件消费了 brief 指定依赖：
  - `buildSidebarModel` / `flattenItems` / `SidebarItem`
  - `DcIcon`
  - `BrandLogo`
  - `useUserStore().menus`
  - `useRoute()` / `useRouter()`
- 导航行为按 brief 实现：
  - 站内路径使用 `router.push`
  - 外链使用 `window.open(..., '_blank', 'noopener')`
- `BrandLogo` 接入情况：
  - 展开态品牌区已使用 `BrandLogo :size="28" :svg-size="20" :radius="8"`
  - 折叠态 rail 顶部已使用 `BrandLogo :size="30" :svg-size="22" :radius="8"`

## 验证命令与结果

1. 在 `ai-work-ui` 目录执行：

```bash
npm run type-check
```

结果：

- 未通过。
- 报错位置：`ai-work-ui/src/layout/menuNav.spec.ts:50`
- 报错内容：`TS2532: Object is possibly 'undefined'.`
- 该报错位于既有测试文件，不在本次新增的 `AppSidebar.vue` 内。

2. 手工浏览器验证：

- 本次未执行。
- 按任务说明如实记录，后续由 Task 7 / Task 11 做整体验证。

## 变更文件

- `ai-work-ui/src/layout/AppSidebar.vue`
- `.superpowers/sdd/task-5-report.md`

## 自审结论

- 本次改动限定在 Task 5 范围内，没有修改布局容器，没有删除或改动 `MenuTreeNode`，没有实现顶栏。
- 侧边栏展开/折叠两态都已按 brief 接入 `BrandLogo`。
- 菜单模型消费、分组展开状态、首页入口与外链跳转都与 brief 一致。

## 问题或疑虑

- 当前仓库的 `npm run type-check` 因既有测试文件 `ai-work-ui/src/layout/menuNav.spec.ts` 的空值检查失败而未通过，这会影响 Task 5 的整体类型检查验收。
- 本次按“只做 Task 5 brief 要求的改动”约束，没有顺手修改该既有测试文件。

## Task 5 后续修复记录（补充）

### 根因
- `menuNav.spec.ts` 在 `noUncheckedIndexedAccess` 下直接读取 `model.looseItems[0].path`，数组索引返回值可能为 `undefined`，触发 `TS2532`。

### 修复内容
- 文件：`ai-work-ui/src/layout/menuNav.spec.ts`
- 将直接索引改为先保存首项再校验：
  - `const firstLooseItem = model.looseItems[0]`
  - `expect(firstLooseItem).toBeDefined()`
  - `expect(firstLooseItem?.path).toBe('/reports')`
- 测试语义保持不变（仍校验首项 id 为 `2` 且 path 归一化为 `/reports`）。

### 验证命令与结果
- `npm run test:unit -- run src/layout/menuNav.spec.ts`：通过（1 个文件，7 个用例通过）。
- `npm run type-check`：通过（无报错）。

### 变更文件
- `ai-work-ui/src/layout/menuNav.spec.ts`
- `.superpowers/sdd/task-5-report.md`

### 自审结论
- 本次修改仅限既有测试文件的类型安全修复，未改业务代码/组件逻辑。
- 修复对现有断言和语义无影响，且满足 `noUncheckedIndexedAccess` 下的 TS 类型要求。
