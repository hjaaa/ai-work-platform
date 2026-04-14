# 开发笔记 - 本地项目类别

## 技术发现
- workspacePath 在 Git 项目中由系统自动生成（basePath/projectId），本地项目改为用户输入
- 删除 Git 项目时会递归删除 workspacePath 目录，本地项目需跳过此逻辑
- codePath 是 workspacePath/code 子目录，本地项目不需要

## What Did NOT Work
- (暂无)
