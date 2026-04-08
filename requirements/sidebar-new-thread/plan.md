# 方案：侧边栏项目行增加"新增线程"入口

## 实现思路

在 `wb-project-item` 中项目名称右侧添加 hover 显示的编辑图标按钮，点击创建新线程并进入聊天模式。

## 改动点

1. **Template**：`wb-project-name` 后添加图标按钮，`@click.stop` 防止触发项目展开
2. **Script**：新增 `handleNewThread(project)` 方法，复用 `createThread` + `enterChatModeForThread`
3. **Style**：按钮默认隐藏，hover 项目行时显示；遵循 DESIGN.md 设计规范
