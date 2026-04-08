# 实施方案

## 改动清单

### 后端（3 文件）

1. **GitService.java** — 接口新增 `retryClone(String projectId)`
2. **GitServiceImpl.java** — 实现 retryClone：校验状态为 failed → 清理旧 code 目录 → 重置状态为 creating → 调用已有 cloneRepository
3. **ProjectController.java** — 新增 `POST /api/projects/{projectId}/retry-clone`

### 前端（2 文件）

4. **project.js** — 新增 `retryClone(projectId)` API 方法
5. **ProjectListView.vue** — 失败卡片 hover 时在 card-actions 中新增刷新按钮，点击调用 retryClone 并启动 WebSocket 监听

## 关键决策

- retry 前清理 `workspacePath/code` 目录，避免 git clone 报目录已存在
- 后端校验项目状态必须为 FAILED，防止误触发
- 前端复用已有的 `watchCloneProgress()` 监听 clone 进度
