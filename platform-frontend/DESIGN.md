# Teambition Design System (提取自线上页面)

基于 Chrome DevTools 从 teambition.com 提取的设计规范。

---

## 1. Visual Theme

- **风格**: 简洁企业协作工具，轻量扁平
- **整体调性**: 白底 + 浅灰背景 + 蓝色品牌主色，克制使用阴影和装饰
- **图标风格**: 线性图标（outline），24px 基准尺寸

## 2. Color Palette

### Brand Colors (CDS Design System)
| Token | 值 | 用途 |
|-------|------|------|
| `--CDS_primary_brand` | `rgba(0, 145, 255, 1)` / `#0091FF` | 主按钮、激活态、品牌色 |
| `--CDS_hover_brand` | `rgba(0, 130, 229, 1)` / `#0082E5` | 按钮 hover |
| `--CDS_click_brand` | `rgba(0, 116, 204, 1)` / `#0074CC` | 按钮 active/pressed |
| `--CDS_heavy_brand` | `rgba(0, 101, 178, 1)` | 加重品牌色 |
| `--CDS_deco_brand` | `rgba(77, 178, 255, 1)` | 装饰色/Logo渐变亮端 |
| `--CDS_text_brand` | `rgba(0, 116, 204, 1)` | 品牌色文字 |
| `--CDS_pale_brand` | `rgba(0, 145, 255, 0.06)` | 品牌色浅底 |
| `--CDS_highlight_brand` | `rgba(0, 145, 255, 0.1)` | 高亮背景 |
| `--CDS_bright_brand` | `rgba(0, 145, 255, 0.16)` | 明亮背景 |
| `--CDS_border_brand` | `rgba(0, 145, 255, 0.4)` | 品牌色边框 |

### Neutral Colors
| Token | 值 | 用途 |
|-------|------|------|
| 页面背景 | `#F9F9F9` / `rgb(249, 249, 249)` | 主内容区背景 |
| 卡片背景 | `#FFFFFF` | 卡片、弹窗、侧边栏 |
| 主文字 | `#262626` / `rgb(38, 38, 38)` | 标题、正文 |
| 次文字 | `rgba(38, 38, 38, 0.76)` | 导航文字、描述文字 |
| 弱文字 | `rgba(38, 38, 38, 0.36)` | 占位符、禁用文字 |
| 分割线 | `rgba(38, 38, 38, 0.06)` | Tab 下划线、分隔线 |
| Active背景 | `rgba(38, 38, 38, 0.06)` | 导航选中态背景 |

### Semantic Colors
| 类别 | Primary | Hover | 用途 |
|------|---------|-------|------|
| Success | `rgba(0, 136, 91, 1)` | `rgba(0, 108, 72, 1)` | 成功状态 |
| Orange | `rgba(249, 146, 0, 1)` | `rgba(212, 125, 0, 1)` | 警告、VIP标记 |
| Purple | `rgba(115, 83, 233, 1)` | `rgba(101, 65, 212, 1)` | 旗舰版标签 |
| Warning | `rgba(255, 154, 33, 1)` | `rgba(229, 136, 16, 1)` | 提醒 |
| Alert | `rgba(255, 204, 0, 1)` | `rgba(235, 184, 0, 1)` | 告警 |

## 3. Typography

### Font Stack
```css
font-family: -apple-system, system-ui, "Segoe UI", Roboto, "Helvetica Neue",
             "PingFang SC", "Noto Sans", "Noto Sans CJK SC",
             "Microsoft YaHei", 微软雅黑, sans-serif;
```

### Font Sizes
| 场景 | 大小 | 字重 |
|------|------|------|
| 页面标题 | 16px | 600 |
| 正文/按钮 | 14px | 400 |
| 导航标签（mini） | 12px | 400 |
| 徽章/角标 | 10px | 500 |

### Text Colors
- 主文字: `#262626`
- 导航文字（未选中）: `rgba(38, 38, 38, 0.76)`
- 导航文字（选中）: `#262626`（加深，与 active bg 配合）
- 品牌标签文字: `rgb(38, 168, 240)` (VIP 标签)

## 4. Component Styling

### Sidebar (导航栏)
```css
width: 80px;
background: #FFFFFF;
z-index: 100;
/* 无明显 border，视觉上通过主内容区灰色背景形成分隔 */
```

- **Logo**: 28x28px, 渐变 `linear-gradient(rgb(0, 137, 255) 0%, rgb(108, 187, 255) 106%)`，圆角 6px
- **导航项**: 垂直排列，图标 + 文字，居中对齐
- **选中态**: `background: rgba(38, 38, 38, 0.06)`, `border-radius: 8px`, 文字色加深为 `#262626`
- **底部区域**: 通知铃铛 + 用户头像（sticky定位）
- **分隔线**: 小横线，水平居中，宽度约 20px，`rgba(38, 38, 38, 0.06)`

### Primary Button (新建项目)
```css
background: #0091FF;
color: #FFFFFF;
border-radius: 8px;
height: 36px;
padding: 0 11px;
font-size: 14px;
font-weight: 400;
/* 带圆形蓝色 + 图标前缀 */
```
- Hover: `#0082E5`
- Active: `#0074CC`

### Secondary Button (小按钮，如模板中的"创建")
```css
background: #0091FF;
color: #FFFFFF;
border-radius: 4px;
height: 28px;
font-size: 12px;
```

### Tab Navigation
```css
font-size: 14px;
color: #262626;
font-weight: 400;
/* 选中态 tab 有底部蓝色指示器 */
border-bottom: 2px solid #0091FF; /* 选中态 */
```

### Project Card (项目卡片)
```css
border-radius: 12px;
box-shadow: rgba(38, 38, 38, 0.1) 0px 1px 5px 0px;
background: #FFFFFF;
/* 卡片内有封面图 + 项目名称 */
```
- 封面图: 填充卡片上部，`border-radius: 12px 12px 0 0`
- 项目名称: 14px, `#262626`, padding 12px

### Create Card (创建项目占位卡片)
```css
border: 1px dashed rgba(38, 38, 38, 0.15);
border-radius: 12px;
background: #FFFFFF;
/* 中心 + 号图标 + "创建项目" 文字 */
```

### Template Card (模板卡片，新建项目弹出区域)
```css
width: 156px;
height: ~98px;
border-radius: 12px;
box-shadow: rgba(38, 38, 38, 0.1) 0px 1px 5px 0px;
```
- 带背景渐变: `linear-gradient(89.97deg, rgba(43, 120, 237, 0.56) 0.06%, rgba(154, 74, 255, 0.56) 99.94%)`

## 5. Layout Principles

### Page Structure
```
+--------+-------------------------------------------+
| Sidebar |  [Top Banner - 可关闭]                    |
| 80px   |  [新建项目 Button]         [显示模板]       |
|        |  [Tab: 我参与 | 我创建 | 我可见 | 更多] ⌂ ··· |
| Logo   |  [Project Card Grid]                       |
| Search |  [Card1] [Card2] [+创建项目]               |
| +New   |                                            |
| ---    |                                            |
| 工作台  |                                            |
| *项目*  |                                            |
| 我的任务 |                                            |
| 企业统计 |                                            |
| 全部    |                                            |
| ---    |                                            |
| 捷径    |                                            |
| 项目集  |                                            |
|        |                                            |
| 🔔     |                                            |
| 头像    |                                            |
+--------+-------------------------------------------+
```

### Content Area
- 左侧 padding: ~24px
- 卡片网格: 自动填充，卡片宽度约 180-200px
- Tab 与卡片间距: ~16px

## 6. Spacing Scale

| Token | 值 |
|-------|------|
| xs | 4px |
| sm | 8px |
| md | 12px |
| lg | 16px |
| xl | 20px |
| 2xl | 24px |
| 3xl | 32px |

## 7. Shadow System

| 场景 | 值 |
|------|------|
| 卡片默认 | `rgba(38, 38, 38, 0.1) 0px 1px 5px 0px` |
| 卡片 hover | `rgba(38, 38, 38, 0.15) 0px 2px 8px 0px` (推测) |
| 弹窗/下拉 | `rgba(38, 38, 38, 0.12) 0px 4px 16px 0px` (推测) |

## 8. Design Guidelines

- **扁平化**: 不使用深重阴影，卡片仅有轻微阴影
- **蓝色主色**: 所有交互态使用 #0091FF 及其变体
- **中性色分级**: 通过 #262626 的不同透明度实现文字层次（1.0/0.76/0.36/0.06）
- **圆角统一**: 小元素 4-8px，卡片 12px
- **图标**: 线性风格，22-24px 尺寸，与文字同色

## 9. Responsive Behavior

- Sidebar: 可折叠为 80px mini 模式（当前状态），展开时约 200px
- 内容区: 流式布局，卡片网格自适应
- 断点: 移动端适配由移动 App 处理，Web 端主要为桌面设计（≥1200px）
