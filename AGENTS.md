# 仓库指南

## 项目结构与模块组织

`ai-work-platform` 通过根 `pom.xml` 聚合各 Spring Cloud 服务。运行时服务位于独立目录：`ai-work-register`（Nacos 注册中心）、`ai-work-gateway`（边缘路由）、`ai-work-auth`（授权）、`ai-work-upms`（用户与权限）、`ai-work-boot`（单体启动器）、`ai-work-visual`（监控、代码生成、quartz）。共享库与 DTO 位于 `ai-work-common`。前端应用位于 `ai-work-ui`（Vue 3 + TypeScript + Vite）。示例 SQL 与 Docker 构建上下文在 `db/`，基础设施编排见 `docker-compose.yml`（微服务形态）与 `docker-compose-boot.yml`（单体形态）。团队规范文档位于 `context/team/`，构建辅助脚本位于 `scripts/`。所有模块均采用标准的 `src/main/java` 与 `src/test/java` 布局。

## 环境要求

- 后端：JDK 17 + Maven；前端：Node `^22.22.2 || ^24.15.0 || >=26.0.0`（见 `ai-work-ui/package.json` engines）。
- 本地运行依赖 MySQL 与 Redis（docker compose 栈已内置）。数据库初始化脚本：`db/ai_work.sql`（业务库）、`db/ai_work_config.sql`（Nacos 配置中心库）。

## 构建、测试与开发命令

后端（项目根目录）：

- `mvn clean install -T 4 -Pcloud` 编译微服务版（`cloud` 为默认激活 profile）。
- `mvn clean install -T 4 -Pboot` 编译单体版（`ai-work-boot` 仅在 `boot` profile 下参与构建）。
- `docker compose build && docker compose up` 构建镜像并启动微服务栈（含 MySQL、Redis；`ai-work-register` 端口 8848/9848，`ai-work-gateway` 统一入口 9999，`ai-work-monitor` 5001）。单体形态使用 `docker compose -f docker-compose-boot.yml up`。

前端（`ai-work-ui` 目录内）：

- `npm run dev` 启动 Vite 开发服务器。
- `npm run build` 执行类型检查（vue-tsc）并构建生产包。
- `npm run lint` / `npm run format` 执行 lint（oxlint + ESLint）与格式化（Prettier）。
- 变更依赖后须在干净环境执行 `npm ci` 验证：macOS 上增量 `npm install` 会裁剪 `package-lock.json` 中跨平台 optional 依赖条目，导致 Linux/CI 构建失败。

## 团队开发规范（必须遵守）

本项目为多语言项目，开发规范按语言划分存放于 `context/team/coding/` 目录，总索引见 [context/team/coding/README.md](context/team/coding/README.md)。编写或修改代码前，先查阅对应语言目录下的规范：

- **Java**（各 `ai-work-*` 后端模块）→ `context/team/coding/java/`，含编程规约：命名、常量、格式、OOP、日期时间、集合、并发、控制语句、注释、前后端交互、其他（`01`~`11`）；错误码、异常与日志（`12`~`14`）；单元测试（`15`）；安全（`16`）；工程结构（`17`~`19`）；设计规约（`20`）；专有名词附录（`21`）
- **MySQL**（`db/` 及各模块 mapper，建表 / 索引 / SQL / ORM）→ `context/team/coding/mysql/`
- **前端**（`ai-work-ui`，Vue 3 + TypeScript）→ `context/team/coding/frontend/`，含通用、HTML、CSS、JavaScript、TypeScript 编码规约（`01`~`05`）与 Vue 组件规约（`06`）；React、Node.js 规约（`07`~`08`）本项目未使用，供其他技术栈项目复用。UI 视觉设计（配色、排版、间距、组件样式）遵循 [ai-work-ui/DESIGN.md](ai-work-ui/DESIGN.md)，编写或修改页面前先阅读该文件

与语言无关的工程协作规范（Git 提交、更新日志、Pull Request）见 `context/team/engineering/`，索引见 [context/team/engineering/README.md](context/team/engineering/README.md)。

其中标注【强制】的条目不允许违反；【推荐】条目除非有充分理由并在代码评审中说明，否则应遵守。

## 提交与 Pull Request

分支模型：`develop` 为日常集成分支，`main` 用于发版；`develop` 为保护分支，禁止直接提交，改动一律经 feature 分支合入。Commit Message、工作流、分支与 Tag 命名遵循团队 Git 规约：[context/team/engineering/01-git.md](context/team/engineering/01-git.md)（`type(scope): summary` 格式，如 `fix(upms): 清理登录失败缓存`）。

PR 描述必须按 [.github/PULL_REQUEST_TEMPLATE.md](.github/PULL_REQUEST_TEMPLATE.md) 的结构填写（规约见 [context/team/engineering/03-pull-request.md](context/team/engineering/03-pull-request.md)）；用 `gh pr create --body` 等方式创建 PR 时模板不会自动应用，须主动按模板结构组织描述。不提交生成产物。
