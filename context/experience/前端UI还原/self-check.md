## 前端 UI 还原自检清单

- [ ] Chrome DevTools MCP 先 take_screenshot 看布局视觉，再 take_snapshot 看元素结构层级，两者配合分析
- [ ] Vue3 + Element Plus 侧边栏布局使用 el-aside + el-container，el-menu 设置 collapse=true 实现窄栏图标导航
- [ ] 改全局布局（App.vue）时同步检查 router，确保原有路由路径不丢失、不冲突
- [ ] WorkbenchView 新增内容面板时：新增 `xxxMode` ref，与已有 `chatMode` 互斥；所有入口函数（如 `handleNewTask`、`selectThread`）都要重置新 mode，避免切换后遗留旧状态
