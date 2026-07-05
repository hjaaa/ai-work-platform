---
version: alpha
name: ai-work-platform-design
description: "AI Work Platform 管理后台设计系统。基于 IBM Carbon Design System 的克制美学（单一蓝色点缀、细线与表面色分层、4px 栅格、高信息密度），本地化适配为 Element Plus 默认主题的令牌值：主色 #409EFF、中文字体栈、4px 圆角。整页浅色，白色画布配浅灰表面带，层级靠 1px 细线与表面色变化而非投影。适用场景为数据密集的企业级管理后台（表格、表单、树、弹窗），不适用于营销页。"

colors:
  primary: "#409eff"
  primary-hover: "#79bbff"
  primary-pressed: "#337ecc"
  primary-tint: "#ecf5ff"
  on-primary: "#ffffff"
  ink: "#303133"
  ink-muted: "#606266"
  ink-subtle: "#909399"
  ink-disabled: "#c0c4cc"
  canvas: "#ffffff"
  surface-1: "#f5f7fa"
  surface-2: "#ebeef5"
  hairline: "#e4e7ed"
  hairline-strong: "#dcdfe6"
  semantic-success: "#67c23a"
  semantic-warning: "#e6a23c"
  semantic-error: "#f56c6c"
  semantic-info: "#909399"

typography:
  display:
    fontFamily: "'Helvetica Neue', Helvetica, 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif"
    fontSize: 28px
    fontWeight: 300
    lineHeight: 1.3
  page-title:
    fontFamily: "'Helvetica Neue', Helvetica, 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif"
    fontSize: 20px
    fontWeight: 500
    lineHeight: 1.4
  card-title:
    fontFamily: "'Helvetica Neue', Helvetica, 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif"
    fontSize: 16px
    fontWeight: 500
    lineHeight: 1.5
  body:
    fontFamily: "'Helvetica Neue', Helvetica, 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif"
    fontSize: 14px
    fontWeight: 400
    lineHeight: 1.57
  body-emphasis:
    fontFamily: "'Helvetica Neue', Helvetica, 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif"
    fontSize: 14px
    fontWeight: 600
    lineHeight: 1.57
  caption:
    fontFamily: "'Helvetica Neue', Helvetica, 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif"
    fontSize: 12px
    fontWeight: 400
    lineHeight: 1.5
  button:
    fontFamily: "'Helvetica Neue', Helvetica, 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif"
    fontSize: 14px
    fontWeight: 400
    lineHeight: 1

rounded:
  none: 0px
  sm: 2px
  base: 4px
  lg: 8px
  pill: 9999px

spacing:
  xxs: 4px
  xs: 8px
  sm: 12px
  md: 16px
  lg: 24px
  xl: 32px
  page: 20px

components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    typography: "{typography.button}"
    rounded: "{rounded.base}"
    padding: 8px 15px
  button-default:
    backgroundColor: "{colors.canvas}"
    textColor: "{colors.ink-muted}"
    typography: "{typography.button}"
    rounded: "{rounded.base}"
    padding: 8px 15px
  button-danger:
    backgroundColor: "{colors.semantic-error}"
    textColor: "{colors.on-primary}"
    typography: "{typography.button}"
    rounded: "{rounded.base}"
    padding: 8px 15px
  button-link:
    backgroundColor: transparent
    textColor: "{colors.primary}"
    typography: "{typography.button}"
    rounded: "{rounded.none}"
    padding: 0
  side-nav:
    backgroundColor: "{colors.canvas}"
    textColor: "{colors.ink-muted}"
    typography: "{typography.body}"
    rounded: "{rounded.none}"
    width: 200px
  top-bar:
    backgroundColor: "{colors.canvas}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.none}"
    height: 56px
  page-container:
    backgroundColor: "{colors.surface-1}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.none}"
    padding: 20px
  card:
    backgroundColor: "{colors.canvas}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.base}"
    padding: 20px
  search-bar:
    backgroundColor: "{colors.canvas}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.base}"
    padding: 16px 20px
  data-table:
    backgroundColor: "{colors.canvas}"
    textColor: "{colors.ink-muted}"
    typography: "{typography.body}"
    rounded: "{rounded.none}"
    padding: 0
  data-table-header:
    backgroundColor: "{colors.surface-1}"
    textColor: "{colors.ink}"
    typography: "{typography.body-emphasis}"
    rounded: "{rounded.none}"
    padding: 8px 12px
  text-input:
    backgroundColor: "{colors.canvas}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.base}"
    padding: 5px 11px
  dialog:
    backgroundColor: "{colors.canvas}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.base}"
    padding: 20px
  tag-status:
    backgroundColor: "{colors.primary-tint}"
    textColor: "{colors.primary}"
    typography: "{typography.caption}"
    rounded: "{rounded.base}"
    padding: 0 9px
---

## 概述

本设计系统源自 **IBM Carbon Design System** 的管理后台适配版。保留 Carbon 的核心气质——克制、精确、工程化——但令牌值全部对齐本项目实际使用的 **Element Plus 默认主题**，避免大面积覆写组件样式。

三条底层原则：

1. **单一蓝色点缀**。`{colors.primary}` 是页面上唯一的品牌色，只用于主按钮、链接、选中态、焦点环。状态语义用固定的绿 / 橙 / 红（`{colors.semantic-success}` / `{colors.semantic-warning}` / `{colors.semantic-error}`），不引入第二品牌色。
2. **细线与表面色分层，不用投影**。卡片、输入框、分隔靠 1px `{colors.hairline}` 细线和 `{colors.canvas}` ↔ `{colors.surface-1}` 的表面色交替表达层级。投影只保留给浮层（下拉、弹窗、消息提示），由 Element Plus 默认样式提供，不额外添加。
3. **信息密度优先**。这是数据密集的企业后台，用户期望一屏看到尽可能多的内容。区块间距走 4px 栅格的小档位（8 / 12 / 16 / 24px），不做营销页式的大留白。

**技术栈约束**：Vue 3 + Element Plus（全量引入、默认主题）+ Tailwind CSS 4。布局用 Tailwind 工具类，组件一律用 Element Plus，不手写重复轮子。

## 色彩

> 所有色值即 Element Plus 默认主题渲染值，组件默认样式天然合规，无需覆写变量。

### 品牌与交互

- **Primary**（{colors.primary}）：唯一品牌色。主按钮、链接、菜单选中态、输入框焦点边框、Tab 选中下划线。
- **Primary Hover**（{colors.primary-hover}）：主按钮悬停。
- **Primary Pressed**（{colors.primary-pressed}）：主按钮按下。
- **Primary Tint**（{colors.primary-tint}）：主色浅底——菜单选中项背景、信息标签底色。除此之外蓝色不做任何背景。

### 表面

- **Canvas**（{colors.canvas}）：卡片、侧边栏、顶栏、弹窗的底色。
- **Surface 1**（{colors.surface-1}）：内容区页面背景、表头背景、禁用输入框——比卡片低一层的"地面"。
- **Surface 2**（{colors.surface-2}）：更深一档的分隔场景（表格斑马纹、深分隔线）。
- **Hairline**（{colors.hairline}）：卡片描边、分隔线、表格行线。
- **Hairline Strong**（{colors.hairline-strong}）：输入框、按钮等交互控件的描边。

### 文本

- **Ink**（{colors.ink}）：标题、正文主内容。
- **Ink Muted**（{colors.ink-muted}）：常规正文、表格单元格、次要按钮文字。
- **Ink Subtle**（{colors.ink-subtle}）：辅助说明、占位说明、次级 meta。
- **Ink Disabled**（{colors.ink-disabled}）：禁用态、占位符。

### 语义

- **Success**（{colors.semantic-success}）/ **Warning**（{colors.semantic-warning}）/ **Error**（{colors.semantic-error}）/ **Info**（{colors.semantic-info}）：仅用于状态反馈（消息、标签、表单校验），不用于装饰。

## 排版

### 字体栈

`'Helvetica Neue', Helvetica, 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif`

中文优先的系统字体栈，与 Element Plus 默认一致，不引入网络字体。层级靠**字号 + 字重**表达，不换字族。

### 层级

| Token | 字号 | 字重 | 行高 | 用途 |
|---|---|---|---|---|
| `{typography.display}` | 28px | 300 | 1.3 | 登录页产品名、数据大屏数字 |
| `{typography.page-title}` | 20px | 500 | 1.4 | 页面主标题 |
| `{typography.card-title}` | 16px | 500 | 1.5 | 卡片标题、弹窗标题、分组标题 |
| `{typography.body}` | 14px | 400 | 1.57 | 默认正文、表格、表单、菜单 |
| `{typography.body-emphasis}` | 14px | 600 | 1.57 | 表头、选中 Tab、强调行 |
| `{typography.caption}` | 12px | 400 | 1.5 | 辅助说明、标签、时间戳 |
| `{typography.button}` | 14px | 400 | 1 | 按钮文字 |

### 原则

- **300 字重只用于大号数字与西文**（源自 Carbon 的轻字重签名）。中文在多数系统字体下没有 300 字重，会回退渲染；中文标题一律用 400 / 500，禁止加粗到 700。
- **中文正文不加字距**。Carbon 的 0.16px 西文字距细节不适用于中文，去除。
- **标签与栏目名用句子式写法**，不用全大写、不加西文式字符间距。
- 14px 是后台的绝对主力字号；小于 12px 的文字禁止出现。

## 布局

### 框架结构

经典侧边栏后台：左侧 `{components.side-nav}`（200px 白底、右侧 1px `{colors.hairline}` 描边）+ 顶部 `{components.top-bar}`（56px 白底、底部 1px 细线）+ 内容区 `{components.page-container}`（`{colors.surface-1}` 浅灰地面）。

内容区的标准节奏：**灰色地面上铺白卡片**。查询区、表格区、图表区各自是一张 `{components.card}`，卡片间距 `{spacing.md}` 16px。

### 间距系统

- **基准单位 4px**（Carbon 栅格），所有间距取 4 的倍数。
- 令牌：`{spacing.xxs}` 4px · `{spacing.xs}` 8px · `{spacing.sm}` 12px · `{spacing.md}` 16px · `{spacing.lg}` 24px · `{spacing.xl}` 32px · `{spacing.page}` 20px（内容区四周留白）。
- 卡片内边距 20px；表单项纵向间距 18px（Element Plus 默认）；按钮组内按钮间距 12px。
- 密度取向：区块间距宁小勿大，表格默认密度即可，不刻意加大行高。

## 层级与深度

| 层级 | 处理 | 用途 |
|---|---|---|
| 0（平面） | 无描边无投影 | 正文、页面背景 |
| 1（细线） | 1px `{colors.hairline}` 描边 | 卡片、表格行、分隔 |
| 2（表面抬升） | `{colors.surface-1}` 或 `{colors.surface-2}` 底色 | 表头、斑马纹、悬停行 |
| 3（焦点） | 1px `{colors.primary}` 描边 | 聚焦的输入框、选中控件 |
| 4（浮层） | Element Plus 默认投影 | 下拉、弹窗、消息——仅此处允许投影 |

`el-card` 一律使用 `shadow="never"`，靠自带的 1px 描边分层。不要给页面内静态元素加任何 `box-shadow`。

## 形状

| Token | 值 | 用途 |
|---|---|---|
| `{rounded.none}` | 0px | 布局容器、表格、分隔线 |
| `{rounded.sm}` | 2px | 小型标记 |
| `{rounded.base}` | 4px | 默认——按钮、输入框、卡片、弹窗、标签 |
| `{rounded.lg}` | 8px | 大型面板（少用） |
| `{rounded.pill}` | 9999px | 开关、圆形头像（仅限组件自带） |

4px 是全局默认圆角（Element Plus 默认值），与 Carbon 的 0px 方角是本适配版的**有意偏离**——保持组件库原生外观，避免全量覆写。同样禁止的方向不变：不用大圆角（>8px）卡片，不用胶囊形按钮。

## 组件

> 全部用 Element Plus 实现，组件名标注对应实现。

### 按钮

- **`button-primary`**（`el-button type="primary"`）：每个视图区块**最多一个**主按钮，承载该区块的首要动作（查询、保存、新增）。
- **`button-default`**（`el-button`）：次要动作（重置、取消、导出）。
- **`button-danger`**（`el-button type="danger"`）：删除等破坏性动作，必须配 `el-popconfirm` 或确认弹窗。
- **`button-link`**（`el-button link type="primary"`）：表格行内操作（编辑、详情、删除），行内并排不超过 3 个，更多动作收进下拉。

### 导航

- **`side-nav`**（`el-aside` 200px + `el-menu router`）：白底，选中项为 `{colors.primary-tint}` 底 + `{colors.primary}` 文字。菜单树由后端权限数据驱动。
- **`top-bar`**（`el-header`）：左侧面包屑/标题，右侧用户下拉。白底，底部 1px 细线。

### 容器

- **`page-container`**（`el-main`）：`{colors.surface-1}` 地面，内边距 `{spacing.page}` 20px。
- **`card`**（`el-card shadow="never"`）:白底 + 1px `{colors.hairline}` 描边 + 4px 圆角，内边距 20px。页面内容的基本单元。

### 数据展示

- **`search-bar`**：列表页顶部的查询条件区，`el-form inline` 置于独立卡片内；末尾"查询"（primary）+"重置"（default）。条件超过 6 个时收起为展开/收起两态。
- **`data-table`**（`el-table`）：表头 `{components.data-table-header}`（`{colors.surface-1}` 底 + `{typography.body-emphasis}`），行线 1px `{colors.hairline}`，悬停行 `{colors.surface-1}`。不加竖线,不加外描边（卡片已提供边界）。
- **分页**（`el-pagination`）：表格下方右对齐，间距 `{spacing.md}`。
- **`tag-status`**（`el-tag`）：状态标签用浅底深字（如 `{colors.primary-tint}` 底 + `{colors.primary}` 字），语义色对应 success / warning / danger 类型。状态是页面上除主色外唯一的用色场景。

### 表单与反馈

- **`text-input`**（`el-input` 等）：白底 + 1px `{colors.hairline-strong}` 描边，聚焦变 `{colors.primary}` 描边。表单校验错误用 Element Plus 默认红色描边 + 底部提示文字。
- **`dialog`**（`el-dialog`）：新增/编辑表单的默认载体，宽度按内容取 500–700px；标题 `{typography.card-title}`,底部右对齐"取消"+"确定"。复杂详情页才使用 `el-drawer` 或独立路由。
- **消息反馈**：操作结果用 `ElMessage`（成功/失败），破坏性确认用 `ElMessageBox.confirm`。

## Do's and Don'ts

### Do

- 每个区块只放一个 `type="primary"` 按钮，蓝色保持稀缺。
- 用 `el-card shadow="never"` + 细线分层；灰地面白卡片是页面的基本节奏。
- 间距全部取 4 的倍数,倾向紧凑档位。
- 中文标题用 400/500 字重；大号数字可用 300。
- 表格行内操作用 `link` 按钮，超过 3 个收进"更多"下拉。
- 状态一律用 `el-tag` 浅底深字，语义色与含义严格对应（绿=正常，橙=警示，红=异常/停用）。
- 新页面优先复用既有组件组合（search-bar + data-table + dialog 三件套），保持全站一致。

### Don't

- 不给静态元素加 box-shadow；投影只属于浮层。
- 不引入第二品牌色；蓝色之外的彩色只能是语义色。
- 不覆写 Element Plus 主题变量或组件内部样式，除非本文档明确要求。
- 不用大圆角（>8px）、胶囊按钮、渐变背景、玻璃拟态——这些都偏离系统气质。
- 不为"美观"加大留白牺牲信息密度；也不允许小于 12px 的文字。
- 不手写与 Element Plus 重复的组件（按钮、弹窗、下拉等一律用组件库）。
- 不在中文文本上加 letter-spacing 或使用全大写英文标签。

## 响应式

管理后台以桌面为先：

| 断点 | 宽度 | 变化 |
|---|---|---|
| Desktop | ≥1280px | 默认布局，侧边栏 200px 展开 |
| Laptop | 1024–1280px | 布局不变，表格出现横向滚动 |
| Tablet | <1024px | 侧边栏收起为图标栏或抽屉；search-bar 表单项换行 |

- 最低支持宽度 1024px，不做移动端专门适配。
- 表格列多时允许容器内横向滚动（`el-table` 自带），禁止页面级横向滚动。
- 弹窗宽度设最大值并允许小屏下退化为 90% 视宽。

## 迭代指南

1. 新增页面时，先套"search-bar 卡片 + data-table 卡片 + dialog 表单"的标准三件套，再考虑特殊布局。
2. 引用组件时使用本文档的 `components:` 令牌名沟通（如"把 card 的内边距改为…"）。
3. 新增组件变体时在 frontmatter 增加独立条目（如 `button-primary-pressed`），不修改既有条目语义。
4. 蓝色使用场景每增加一处都要自问：这是"首要动作/选中态/链接"之一吗？不是就换灰阶。
5. 若未来引入暗色主题或替换主色，只改 frontmatter 令牌值,正文规则不动。
