# 开发笔记

## 技术发现
- CC CLI 的 -p 模式虽然不解析 slash command，但会加载项目的 CLAUDE.md、skill 定义等上下文，因此 agent 可以根据自然语言描述主动调用 Skill 工具
- CC CLI session 机制：首次用 --session-id，后续用 --resume，session 数据由 CC CLI 持久化在 ~/.claude/ 下
