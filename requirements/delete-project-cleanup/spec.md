# 项目删除时同步清理磁盘代码目录

## 背景
删除项目时只做了逻辑删除，磁盘上的 workspacePath 目录（含 clone 的代码）残留，长期积累浪费磁盘空间。

## 需求
在 `ProjectServiceImpl.deleteProject()` 中，逻辑删除前递归删除 `project.getWorkspacePath()` 目录。

## 验收标准
- [ ] 删除项目后，对应的 workspacePath 目录被清理
- [ ] 文件删除失败不阻塞逻辑删除（降级为 warn 日志）
