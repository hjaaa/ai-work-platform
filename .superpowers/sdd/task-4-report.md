# Task 4 报告：menuNav 菜单→导航模型（纯逻辑）

## 实现内容
- 新增 `ai-work-ui/src/layout/menuNav.ts`，提供 `SidebarItem`、`SidebarGroup`、`SidebarModel` 三个类型，以及 `buildSidebarModel`、`flattenItems` 两个纯逻辑函数。
- 逻辑按 brief 迁移自旧 `MenuTreeNode.vue`：`menuType === '1'` 的按钮节点与 `meta.isHide === true` 视为不可见；外链通过 `path` 以 `http(s)://` 开头识别；内部路由自动补前导 `/`。
- 顶级可见子项节点输出为 `groups`，顶级叶子节点输出为 `looseItems`；`flattenItems` 按 `looseItems + groups.items` 扁平化。
- 新增 `ai-work-ui/src/layout/menuNav.spec.ts`，覆盖分组、隐藏项过滤、外链解析、内部路径规范化、叶子项归类与扁平化顺序。

## RED / GREEN 证据
- RED 命令：`npm run test:unit -- run src/layout/menuNav.spec.ts`
- RED 关键输出：
  - `Error: Failed to resolve import "./menuNav" from "src/layout/menuNav.spec.ts". Does the file exist?`
  - `Test Files 1 failed (1)`
  - `Tests  no tests`
- GREEN 命令：`npm run test:unit -- run src/layout/menuNav.spec.ts`
- GREEN 关键输出：
  - `Test Files  1 passed (1)`
  - `Tests  5 passed (5)`

## 变更文件
- `ai-work-ui/src/layout/menuNav.spec.ts`
- `ai-work-ui/src/layout/menuNav.ts`

## 自审结论
- 变更范围符合 Task 4 brief，仅实现菜单树到侧边栏模型的纯逻辑转换，没有修改旧组件、路由或视觉相关代码。
- 测试先失败后通过，且结果与 brief 的 5 个用例一致。
- 代码保持最小实现，没有引入额外抽象。

## 问题或疑虑
- 当前仅按 brief 验证了 `menuNav` 转换逻辑，尚未额外跑整个前端类型检查或全量测试；若后续 `AppSidebar` 接入时有接口约束变化，需要再补集成层验证。
