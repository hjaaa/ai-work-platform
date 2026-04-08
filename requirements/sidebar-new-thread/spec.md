# 侧边栏项目行增加"新增线程"入口

## 需求描述

在工作台（WorkbenchView）左侧面板的项目列表中，每个项目名称右侧增加一个"新增线程"图标按钮。

## 交互规则

- 按钮默认隐藏，hover 项目行时显示
- 图标样式：编辑/compose 铅笔图标（参考 Image 5）
- Tooltip：`在 {项目名称} 中开始新线程`
- 点击行为：创建新线程并进入聊天模式

## 影响范围

- 仅涉及 `WorkbenchView.vue`（template + style）
- 复用已有的 `createThread` API 和 `enterChatModeForThread` 函数
