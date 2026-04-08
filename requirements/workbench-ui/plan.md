# 实施方案

## 改动文件
- `platform-frontend/src/views/WorkbenchView.vue` — 重写模板和样式

## 实施步骤
1. 重写 WorkbenchView.vue 模板：左侧面板 + 主内容区
2. 左侧面板：新任务/技能和应用按钮 + 项目列表（复用现有 API）
3. 主内容区：标题 + 中心构建提示 + 底部聊天输入框
4. 样式遵循 DESIGN.md（颜色 #262626/#0091FF，圆角 8/12px，阴影规范）
5. 浏览器截图验证
