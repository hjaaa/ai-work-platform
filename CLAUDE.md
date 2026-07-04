# 项目约定

仓库结构、构建/测试命令、提交规范见 [AGENTS.md](AGENTS.md)。

## 团队开发规范（必须遵守）

本项目为多语言项目，开发规范按语言划分存放于 `context/team/dev-standards/` 目录，总索引见 [context/team/dev-standards/README.md](context/team/dev-standards/README.md)。编写或修改代码前，先查阅对应语言目录下的规范：

- **Java**（各 `ai-work-*` 后端模块）→ `context/team/dev-standards/java/`，含编程规约：命名、常量、格式、OOP、日期时间、集合、并发、控制语句、注释、前后端交互、其他（`01`~`11`）；错误码、异常与日志（`12`~`14`）；单元测试（`15`）；安全（`16`）；工程结构（`17`~`19`）；设计规约（`20`）；专有名词附录（`21`）
- **MySQL**（`db/` 及各模块 mapper，建表 / 索引 / SQL / ORM）→ `context/team/dev-standards/mysql/`
- **前端**（`ai-work-ui`，Vue 3 + TypeScript）→ `context/team/dev-standards/frontend/`，含通用、HTML、CSS、JavaScript、TypeScript 编码规约（`01`~`05`）与 Vue 组件规约（`06`）

其中标注【强制】的条目不允许违反；【推荐】条目除非有充分理由并在代码评审中说明，否则应遵守。
