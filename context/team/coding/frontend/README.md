# 前端开发规范（团队规范）

适用范围：Vue 3 + TypeScript + Vite 技术栈的前端工程。

约束级别（【强制】/【推荐】/【参考】）说明见 [../README.md](../README.md)。

## 章节目录

| 文档 | 内容 |
|---|---|
| [01-common.md](01-common.md) | 通用编码规约（缩进、行宽、字符集） |
| [02-html.md](02-html.md) | HTML 编码规约 |
| [03-css.md](03-css.md) | CSS 编码规约 |
| [04-javascript.md](04-javascript.md) | JavaScript 编码规约 |
| [05-typescript.md](05-typescript.md) | TypeScript 编码规约 |
| [06-vue.md](06-vue.md) | Vue 组件规约（命名、Props 与通信、模板、SFC 结构、状态管理） |

## 与工具链的关系

- 文中标注 eslint / stylelint 规则名的条目大多可由工具自动检查，是否启用以所在工程的 lint 配置为准；提交前须通过工程的 lint 与类型检查
- 格式类条目（缩进、行宽、引号、分号等）以工程的 Prettier 与 EditorConfig 实配为准，冲突时以工具实配为先
