# 实施方案

## 改动
仅修改 `ProjectServiceImpl.deleteProject()`：在 `projectMapper.deleteById()` 前，递归删除 `project.getWorkspacePath()` 目录。删除失败 warn 日志，不抛异常。
