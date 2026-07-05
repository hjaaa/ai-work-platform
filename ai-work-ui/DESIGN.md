---
version: alpha
name: ai-work-platform-design
description: "AI Work Platform 管理后台设计系统。源自 Teambition（钉钉项目）的轻盈圆润美学：#0091ff 单一品牌蓝且极度稀缺、#262626 透明度阶梯构建全部中性色、环形投影替代硬描边、按容器尺寸分层的大圆角（6/8/12/16px）、中文字重不超过 500。整页浅色，#f9f9f9 灰色地面上悬浮白色圆角面板。技术栈为 Element Plus + Tailwind CSS 4，通过覆写 --el-* CSS 变量落地。适用于数据密集的企业级管理后台。"

colors:
  primary: "#0091ff"
  primary-deep: "#0074cc"
  primary-tint: "rgba(0, 145, 255, 0.1)"
  primary-tint-border: "rgba(0, 145, 255, 0.16)"
  on-primary: "#ffffff"
  ink: "#262626"
  ink-muted: "rgba(38, 38, 38, 0.76)"
  ink-subtle: "rgba(38, 38, 38, 0.6)"
  ink-disabled: "rgba(38, 38, 38, 0.4)"
  canvas: "#f9f9f9"
  surface: "#ffffff"
  fill-1: "rgba(38, 38, 38, 0.03)"
  fill-2: "rgba(38, 38, 38, 0.06)"
  hairline: "rgba(38, 38, 38, 0.1)"
  ring: "rgba(31, 34, 37, 0.08)"
  semantic-success: "#00885b"
  semantic-warning: "#ff9a21"
  semantic-error: "#d04934"
  accent-purple: "#a1a4d9"
  mask: "rgba(68, 71, 75, 0.5)"

typography:
  display:
    fontFamily: "-apple-system, system-ui, 'Segoe UI', Roboto, 'Helvetica Neue', 'PingFang SC', 'Noto Sans', 'Noto Sans CJK SC', 'Microsoft YaHei', sans-serif"
    fontSize: 24px
    fontWeight: 500
    lineHeight: 1.33
  page-title:
    fontFamily: "-apple-system, system-ui, 'Segoe UI', Roboto, 'Helvetica Neue', 'PingFang SC', 'Noto Sans', 'Noto Sans CJK SC', 'Microsoft YaHei', sans-serif"
    fontSize: 20px
    fontWeight: 500
    lineHeight: 1.4
  card-title:
    fontFamily: "-apple-system, system-ui, 'Segoe UI', Roboto, 'Helvetica Neue', 'PingFang SC', 'Noto Sans', 'Noto Sans CJK SC', 'Microsoft YaHei', sans-serif"
    fontSize: 16px
    fontWeight: 500
    lineHeight: 1.5
  body:
    fontFamily: "-apple-system, system-ui, 'Segoe UI', Roboto, 'Helvetica Neue', 'PingFang SC', 'Noto Sans', 'Noto Sans CJK SC', 'Microsoft YaHei', sans-serif"
    fontSize: 14px
    fontWeight: 400
    lineHeight: 1.43
  body-emphasis:
    fontFamily: "-apple-system, system-ui, 'Segoe UI', Roboto, 'Helvetica Neue', 'PingFang SC', 'Noto Sans', 'Noto Sans CJK SC', 'Microsoft YaHei', sans-serif"
    fontSize: 14px
    fontWeight: 500
    lineHeight: 1.43
  caption:
    fontFamily: "-apple-system, system-ui, 'Segoe UI', Roboto, 'Helvetica Neue', 'PingFang SC', 'Noto Sans', 'Noto Sans CJK SC', 'Microsoft YaHei', sans-serif"
    fontSize: 12px
    fontWeight: 400
    lineHeight: 1.67
  button:
    fontFamily: "-apple-system, system-ui, 'Segoe UI', Roboto, 'Helvetica Neue', 'PingFang SC', 'Noto Sans', 'Noto Sans CJK SC', 'Microsoft YaHei', sans-serif"
    fontSize: 14px
    fontWeight: 400
    lineHeight: 1

rounded:
  none: 0px
  sm: 6px
  base: 8px
  md: 10px
  lg: 12px
  xl: 16px
  pill: 9999px

spacing:
  xxs: 4px
  xs: 8px
  sm: 12px
  md: 16px
  lg: 24px
  xl: 32px
  page: 16px

components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    typography: "{typography.button}"
    rounded: "{rounded.base}"
    height: 36px
    padding: 0 12px
  button-default:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.button}"
    rounded: "{rounded.base}"
    height: 36px
    padding: 0 12px
    boxShadow: "0 0 0 1px {colors.ring}, 0 2px 4px -2px rgba(0, 0, 0, 0.04), 0 2px 8px -2px rgba(0, 0, 0, 0.04)"
  button-danger:
    backgroundColor: "{colors.semantic-error}"
    textColor: "{colors.on-primary}"
    typography: "{typography.button}"
    rounded: "{rounded.base}"
    height: 36px
    padding: 0 12px
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
  side-nav-item:
    backgroundColor: transparent
    textColor: "{colors.ink-muted}"
    typography: "{typography.body}"
    rounded: "{rounded.sm}"
    height: 36px
  side-nav-item-active:
    backgroundColor: "{colors.fill-2}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.sm}"
    height: 36px
  top-bar:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.none}"
    height: 56px
  page-container:
    backgroundColor: "{colors.canvas}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.none}"
    padding: 16px
  card:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.lg}"
    padding: 20px
    boxShadow: "0 0 0 1px {colors.ring}, 0 1px 4px 0 rgba(0, 0, 0, 0.04)"
  card-raised:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.xl}"
    padding: 20px
    boxShadow: "0 0 0 1px {colors.ring}, 0 8px 16px -2px rgba(0, 0, 0, 0.04), 0 2px 8px 0 rgba(0, 0, 0, 0.04)"
  search-bar:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.lg}"
    padding: 16px 20px
  data-table:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink-muted}"
    typography: "{typography.body}"
    rounded: "{rounded.none}"
    padding: 0
  data-table-header:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.none}"
    height: 36px
    padding: 0 12px
  text-input:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.sm}"
    border: "1px solid {colors.hairline}"
    height: 32px
    padding: 0 8px
  dialog:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.lg}"
    padding: 20px
  dropdown-menu:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.lg}"
    padding: 8px
  dropdown-menu-item:
    backgroundColor: transparent
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.base}"
    height: 36px
    padding: 0 8px
  tag-status:
    backgroundColor: "{colors.fill-2}"
    textColor: "{colors.ink-muted}"
    typography: "{typography.caption}"
    rounded: "{rounded.sm}"
    height: 24px
    padding: 0 8px
  filter-chip-active:
    backgroundColor: "{colors.primary-tint}"
    textColor: "{colors.primary-deep}"
    typography: "{typography.caption}"
    rounded: "{rounded.pill}"
    border: "1px solid {colors.primary-tint-border}"
    height: 28px
    padding: 4px 8px
---

## 概述

本设计系统源自 **Teambition（钉钉项目）** 的视觉语言，全部令牌值取自对 teambition.com 线上应用真实 computed styles 的采样。核心气质：**轻盈、圆润、灰白分层、蓝色稀缺**。

四条底层原则：

1. **中性色只有一个色相**。全部灰阶由 `{colors.ink}`（#262626）的透明度阶梯生成：3% 做容器底、6% 做选中态与标签底、10% 做发丝线、40%~76% 做次级文字。不引入第二种灰。
2. **环形投影替代硬描边**。卡片、按钮、面板不用 `border`，用 `0 0 0 1px {colors.ring}` 的环形投影充当边界，再叠一层极淡的散射投影表达悬浮。静态卡片允许投影——这是本系统与传统"细线分层"后台的关键差异。
3. **蓝色比你以为的更稀缺**。`{colors.primary}` 只出现在：主按钮、文字链接、激活的筛选章、Tab 选中下划线。**导航选中态用 6% 灰（`{colors.fill-2}`），不用蓝色浅底**——这是 Teambition 最易被忽略的签名特征。
4. **字重封顶 500**。全站没有一处 600/700 加粗。层级靠字号（12/14/16/20/24）与 400/500 两档字重表达。

**技术栈约束**：Vue 3 + Element Plus（全量引入）+ Tailwind CSS 4。布局用 Tailwind 工具类，组件一律用 Element Plus。品牌色、圆角等通过覆写 `--el-*` CSS 变量全局落地（见"Element Plus 适配"一节），禁止逐组件散写内联样式。

## 色彩

> 所有色值采样自 Teambition 线上应用。

### 品牌与交互

- **Primary**（{colors.primary}）：唯一品牌色。主按钮、文字链接、Tab 选中下划线、聚焦边框。
- **Primary Deep**（{colors.primary-deep}）：蓝色浅底上的文字用这个更深的蓝，保证对比度（如筛选章）。
- **Primary Tint**（{colors.primary-tint}）：蓝色 10% 浅底——激活的筛选章、动态过滤 pill 的选中态。除此之外蓝色不做任何背景。
- **Primary Tint Border**（{colors.primary-tint-border}）：蓝色浅底控件的 1px 描边。

### 表面与中性色

- **Canvas**（{colors.canvas}）：页面地面，全站唯一的"灰"。
- **Surface**（{colors.surface}）：卡片、面板、弹窗、表格的底色。
- **Fill 1**（{colors.fill-1}）：容器级浅灰底（看板列、代码块底）。
- **Fill 2**（{colors.fill-2}）：选中态与中性标签的底色（导航选中项、状态标签）。
- **Hairline**（{colors.hairline}）：输入框描边、表格行线、分隔线。
- **Ring**（{colors.ring}）：环形投影色，卡片与按钮的"边界"。

### 文本

- **Ink**（{colors.ink}）：标题、正文、表头。不用纯黑 #000。
- **Ink Muted**（{colors.ink-muted}）：次级正文、未选中导航、标签文字。
- **Ink Subtle**（{colors.ink-subtle}）：辅助说明、图标默认色、场景标记。
- **Ink Disabled**（{colors.ink-disabled}）：禁用态、关闭态开关。

### 语义

- **Success**（{colors.semantic-success}）/ **Warning**（{colors.semantic-warning}）/ **Error**（{colors.semantic-error}）：状态反馈专用。标签形态一律**浅底深字**：语义色 10% 透明度做底 + 语义色原色做字（如 `rgba(0,136,91,0.1)` 底 + `#00885b` 字）。
- **Accent Purple**（{colors.accent-purple}）：数据可视化的补充色（统计图表第 4 色），不用于交互控件。
- **Mask**（{colors.mask}）：弹窗遮罩。

## 排版

### 字体栈

`-apple-system, system-ui, 'Segoe UI', Roboto, 'Helvetica Neue', 'PingFang SC', 'Noto Sans', 'Noto Sans CJK SC', 'Microsoft YaHei', sans-serif`

系统字体优先（与 Teambition 一致），不引入网络字体。

### 层级

| Token | 字号 | 字重 | 行高 | 用途 |
|---|---|---|---|---|
| `{typography.display}` | 24px | 500 | 1.33 | 登录页欢迎语、工作台问候、数据大屏数字 |
| `{typography.page-title}` | 20px | 500 | 1.4 | 页面主标题 |
| `{typography.card-title}` | 16px | 500 | 1.5 | 卡片标题、弹窗标题 |
| `{typography.body}` | 14px | 400 | 1.43 | 默认正文、表格、表单、菜单、**表头** |
| `{typography.body-emphasis}` | 14px | 500 | 1.43 | 选中 Tab、强调文本、任务标题 |
| `{typography.caption}` | 12px | 400 | 1.67 | 标签、辅助说明、时间戳 |
| `{typography.button}` | 14px | 400 | 1 | 按钮文字（小尺寸主按钮 12px/500） |

### 原则

- **字重封顶 500，全站禁用 600/700**。加粗的冲动一律换成"加大一档字号"或"提高一档文字色"。
- **表头不加粗**：表格 header 与 body 同为 14px/400，靠 36px 行高与位置表达身份（Teambition 实测值）。
- 中文不加 letter-spacing，不用全大写英文标签。
- 14px 是绝对主力字号；小于 12px 的文字禁止出现。

## 布局

### 框架结构

**灰色地面上悬浮白色圆角面板**：`{colors.canvas}` 铺满视口作为地面，内容承载在 `{rounded.lg}` 12px 圆角的白色面板/卡片上,面板用环形投影而非描边划界。

后台框架：左侧 `{components.side-nav}`（200px，**与地面同为 {colors.canvas}，无描边无投影**，导航项为 36px 高、6px 圆角的可选中条目）+ 顶部 `{components.top-bar}`（56px 白底）+ 内容区 `{components.page-container}`。

内容区节奏：查询区、表格区各自是一张 `{components.card}`（12px 圆角白卡），卡片间距 `{spacing.md}` 16px。

### 间距系统

- 基准单位 4px，所有间距取 4 的倍数。
- 令牌：`{spacing.xxs}` 4px · `{spacing.xs}` 8px · `{spacing.sm}` 12px · `{spacing.md}` 16px · `{spacing.lg}` 24px · `{spacing.xl}` 32px · `{spacing.page}` 16px。
- 卡片内边距 20px（紧凑场景 14px）；表单项纵向间距 18px（Element Plus 默认）；工具栏控件间距 8px。
- 密度取向：比传统企业后台松一档——控件高度 32~36px，列表行高 44px 上下，留白宁松勿挤，但表格默认密度即可。

## 层级与深度

投影哲学：**边界用环形投影,悬浮用低透明度大半径散射**。所有投影的 alpha 不超过 0.08。

| 层级 | 处理 | 用途 |
|---|---|---|
| 0（地面） | `{colors.canvas}` 平铺 | 页面背景、侧边栏 |
| 1（贴地容器） | `{colors.fill-1}` 底色，无投影 | 看板列、分组容器 |
| 2（卡片） | `0 0 0 1px {colors.ring}` + `0 1px 4px rgba(0,0,0,0.04)` | 列表卡片、任务卡 |
| 3（抬升卡片） | ring + `0 8px 16px -2px rgba(0,0,0,0.04)` + `0 2px 8px rgba(0,0,0,0.04)` | 工作台仪表卡、独立面板 |
| 4（浮层） | ring + `0 24px 48px rgba(0,0,0,0.04)` + `0 4px 16px rgba(0,0,0,0.02)`；弹窗配 `{colors.mask}` 遮罩 | 下拉菜单、弹窗、抽屉 |

输入框、表格行线仍用 1px `{colors.hairline}` 发丝线——发丝线负责"内部分隔"，环形投影负责"外部边界"。

## 形状

圆角按**容器尺寸分层**，越大的容器圆角越大——这是本系统最直观的签名：

| Token | 值 | 用途 |
|---|---|---|
| `{rounded.none}` | 0px | 布局容器、分隔线 |
| `{rounded.sm}` | 6px | 小控件——标签、小输入框、导航项、小按钮 |
| `{rounded.base}` | 8px | 默认——按钮、输入框、菜单项 |
| `{rounded.md}` | 10px | 列表卡片、任务卡 |
| `{rounded.lg}` | 12px | 区块卡片、下拉菜单、弹窗、看板列 |
| `{rounded.xl}` | 16px | 仪表盘大卡、营销面卡片 |
| `{rounded.pill}` | 9999px | 筛选章、开关、头像 |

禁止方向：不用直角卡片，不用 4px 以下的"小气"圆角，也不给普通按钮用胶囊形（胶囊只属于筛选章）。

## 组件

> 全部用 Element Plus 实现，通过 CSS 变量对齐令牌（见下节）。

### 按钮

- **`button-primary`**（`el-button type="primary"`）：{colors.primary} 底 + 白字,36px 高、8px 圆角。每个视图区块**最多一个**。
- **`button-default`**（`el-button`）：白底 + 环形投影（无描边），承载次要动作。
- **`button-danger`**（`el-button type="danger"`）：{colors.semantic-error} 底,破坏性动作,必须配确认弹窗。
- **`button-link`**（`el-button link type="primary"`）：行内文字链接（表格行内操作、"添加 xx"），前置 `+` 图标时表示新增。

### 导航

- **`side-nav`**：与地面同色（{colors.canvas}）、无描边。导航项 `{components.side-nav-item}` 36px 高、6px 圆角;**选中态 = `{colors.fill-2}` 灰底 + `{colors.ink}` 文字,不用蓝色**。菜单树由后端权限数据驱动。
- **`top-bar`**：白底 56px,左侧面包屑,右侧用户下拉,底部 1px `{colors.hairline}`。
- **Tab**：文字型 Tab,选中项 `{typography.body-emphasis}` + 2px {colors.primary} 短下划线,未选中 `{colors.ink-muted}`。

### 容器

- **`page-container`**（`el-main`）：{colors.canvas} 地面,内边距 {spacing.page}。
- **`card`**（`el-card shadow="never"` + 自定义 ring 投影类）：白底、12px 圆角、环形投影,内边距 20px。
- **`card-raised`**：工作台/仪表盘用的 16px 圆角大卡。

### 数据展示

- **`search-bar`**：列表页查询区,`el-form inline` 置于独立卡片;末尾"查询"（primary）+"重置"（default）。
- **`data-table`**（`el-table`）：表头白底 36px、14px/400 **不加粗**;行线 1px `{colors.hairline}`;悬停行 `{colors.fill-1}`。不加竖线、不加外描边。
- **分页**（`el-pagination`）：表格下方右对齐。
- **`tag-status`**（`el-tag`）：中性状态用 `{colors.fill-2}` 底 + `{colors.ink-muted}` 字;语义状态用语义色 10% 底 + 语义色字。一律 24px 高、6px 圆角、12px 字。
- **`filter-chip-active`**：工具栏激活筛选的胶囊章,蓝 10% 底 + {colors.primary-deep} 字 + 16% 蓝描边。

### 表单与反馈

- **`text-input`**（`el-input` 等）：白底 + 1px `{colors.hairline}` 描边、6~8px 圆角、32px 高;聚焦变 {colors.primary} 描边。错误态红描边 + 底部 {colors.semantic-error} 提示。
- **`dialog`**（`el-dialog`）：12px 圆角,遮罩 `{colors.mask}`;标题 `{typography.card-title}`,底部右对齐"取消"+"确定"。
- **`dropdown-menu`**（`el-dropdown` / `el-select` 浮层）：12px 圆角、8px 内边距;菜单项 36px 高、8px 圆角,悬停 `{colors.fill-1}`。
- **消息反馈**：`ElMessage`（成功/失败）,破坏性确认用 `ElMessageBox.confirm`。

### 登录页（特殊表面）

登录页允许突破后台规则：浅蓝渐变地面 + 低饱和漂浮装饰形状 + 大投影白卡（12px 圆角）+ 下划线式输入框 + 左对齐 display 级欢迎语。已实现于 `src/views/login/index.vue`,新营销面页面参照它。

## Element Plus 适配

品牌与形状令牌通过全局 CSS 变量覆写落地,统一维护于 `src/styles/theme.css`（须在 `element-plus/dist/index.css` 之后引入）：

```css
:root {
  --el-color-primary: #0091ff;
  /* primary/success/warning/danger/error 各自的 light-3/5/7/8/9、dark-2
     派生色为静态变量,须按混色公式（light-N = 与白色按 N*10% 混合,
     dark-2 = 与黑色按 20% 混合）一并覆写,完整清单见 theme.css */
  --el-color-success: #00885b;
  --el-color-warning: #ff9a21;
  --el-color-danger: #d04934;
  --el-color-error: #d04934;
  --el-text-color-primary: #262626;
  --el-text-color-regular: rgba(38, 38, 38, 0.76);
  --el-text-color-secondary: rgba(38, 38, 38, 0.6);
  --el-text-color-placeholder: rgba(38, 38, 38, 0.4);
  --el-text-color-disabled: rgba(38, 38, 38, 0.4);
  --el-border-color: rgba(38, 38, 38, 0.1);
  --el-border-color-light: rgba(38, 38, 38, 0.1);
  --el-border-color-lighter: rgba(38, 38, 38, 0.1);
  --el-border-radius-base: 8px;
  --el-border-radius-small: 6px;
  --el-bg-color-page: #f9f9f9;
  --el-fill-color-light: rgba(38, 38, 38, 0.03);
}
```

变量覆写不到的部位（卡片环形投影、菜单 12px 圆角、表头字重、标签配色）用少量全局工具类补齐,集中放在同一主题样式文件中;**禁止在业务组件里逐个覆写 Element Plus 内部类**。业务代码引用品牌色/语义色时一律写 `var(--el-color-*)`,不硬编码色值。

## Do's and Don'ts

### Do

- 导航/列表的选中态用 `{colors.fill-2}` 灰底,蓝色只留给主按钮、链接、Tab 下划线。
- 卡片边界用环形投影（`0 0 0 1px ring`）,内部分隔用发丝线。
- 圆角跟着容器走：小控件 6、按钮 8、卡片 12、大卡 16。
- 全部中性色从 #262626 的透明度阶梯取值,不发明新灰。
- 状态标签浅底深字：语义色 10% 底 + 原色字。
- 中文最大字重 500;表头不加粗。
- 新页面优先复用"search-bar 卡片 + data-table 卡片 + dialog 表单"三件套。

### Don't

- 不用 `border: 1px solid` 做卡片边界（那是输入框和表格行线的写法）。
- 不给导航选中态上蓝色浅底——那是旧 Element Plus 后台的惯性,不是本系统的语言。
- 不用 600/700 字重,不用纯黑 #000。
- 蓝色不做大面积背景;语义色之外不引入新彩色（紫色仅限图表）。
- 投影 alpha 不超过 0.08,禁止黑重投影。
- 不做小于 6px 的圆角,普通按钮不用胶囊形。
- 不在业务组件里散写 Element Plus 内部类覆写;主题级调整一律进全局主题文件。
- 不允许小于 12px 的文字。

## 响应式

管理后台以桌面为先：

| 断点 | 宽度 | 变化 |
|---|---|---|
| Desktop | ≥1280px | 默认布局,侧边栏 200px 展开 |
| Laptop | 1024–1280px | 布局不变,表格出现横向滚动 |
| Tablet | <1024px | 侧边栏收起为图标栏或抽屉;search-bar 表单项换行 |

- 最低支持宽度 1024px,不做移动端专门适配。
- 表格列多时容器内横向滚动（`el-table` 自带）,禁止页面级横向滚动。
- 弹窗宽度设最大值并允许小屏下退化为 90% 视宽。

## 迭代指南

1. 新增页面先套"search-bar 卡片 + data-table 卡片 + dialog 表单"三件套,再考虑特殊布局。
2. 引用组件时使用本文档的 `components:` 令牌名沟通。
3. 新增组件变体时在 frontmatter 增加独立条目,不修改既有条目语义。
4. 蓝色使用场景每增加一处都要自问：这是"主按钮/链接/Tab 下划线/激活筛选"之一吗？不是就换 `{colors.fill-2}` 灰阶。
5. 若替换主色或引入暗色主题,只改 frontmatter 令牌值与 `--el-*` 变量映射,正文规则不动。
