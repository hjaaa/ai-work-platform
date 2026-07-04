# 前端开发规范（团队规范）

适用范围：`ai-work-ui`（Vue 3 + TypeScript + Vite + Pinia + Element Plus + Tailwind CSS）。

约束级别（【强制】/【推荐】/【参考】）说明见 [../README.md](../README.md)。

## 章节目录

| 文档 | 内容 |
|---|---|
| [01-common.md](01-common.md) | 通用编码规约（缩进、行宽、字符集） |
| [02-html.md](02-html.md) | HTML 编码规约 |
| [03-css.md](03-css.md) | CSS 编码规约 |
| [04-javascript.md](04-javascript.md) | JavaScript 编码规约 |
| [05-typescript.md](05-typescript.md) | TypeScript 编码规约 |

## Vue 组件规约

Vue 组件规约文档待补充，编写组件时暂按以下基线执行：

- 组件与代码风格遵循 [Vue 官方风格指南](https://vuejs.org/style-guide/)（Priority A/B 必须遵守）
- TypeScript 保持 strict 模式，不使用 `any` 规避类型检查

## 与工具链的关系

- 文中标注 eslint / stylelint 规则名的条目大多可由工具自动检查；本项目 lint 链为 oxlint + ESLint + Prettier，提交前必须通过 `npm run lint` 与 `npm run type-check`
- 格式类条目（缩进、行宽、引号、分号等）以项目 `.prettierrc.json` 与 `.editorconfig` 实配为准，冲突时以工具实配为先
