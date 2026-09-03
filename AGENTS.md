# 仓库指南

## 项目结构与模块组织

`ai-work-platform` 通过根 `pom.xml` 聚合各 Spring Cloud 服务。运行时服务位于独立目录：`ai-work-register`（Nacos 注册中心）、`ai-work-gateway`（边缘路由）、`ai-work-auth`（授权）、`ai-work-upms`（用户与权限）、`ai-work-baas`（BaaS 平台，当前主开发区，设计单一事实源见 [context/specs/2026-07-17-baas-core-mvp-design.md](context/specs/2026-07-17-baas-core-mvp-design.md)）、`ai-work-boot`（单体启动器）、`ai-work-visual`（监控、代码生成、quartz）。共享库与 DTO 位于 `ai-work-common`。前端应用位于 `ai-work-ui`（Vue 3 + TypeScript + Vite）。示例 SQL 与 Docker 构建上下文在 `db/`，业务服务编排见 `docker-compose.yml`（微服务形态）与 `docker-compose-boot.yml`（单体形态）；MySQL / Redis / Nacos 由公共基础设施栈提供，不在本仓库编排内（`ai-work-register` 模块保留作回退用，已不参与运行）。团队规范文档位于 `context/team/`，构建辅助脚本位于 `scripts/`。所有模块均采用标准的 `src/main/java` 与 `src/test/java` 布局。

## 环境要求

- 后端：JDK 17 + Maven；前端：Node `^22.22.2 || ^24.15.0 || >=26.0.0`（见 `ai-work-ui/package.json` engines）。
- 本地运行依赖 MySQL、Redis 与 Nacos，三者由**跨项目共享的公共基础设施栈**提供（`~/docker-data/infra/docker-compose.yml`，容器名 `dev-mysql` / `dev-redis` / `dev-nacos`），不再随本仓库编排、也不纳入本仓库版本控制。
  - **公共网络**：首次使用需一次性创建 `docker network create --driver bridge --subnet 172.28.0.0/16 --ip-range 172.28.1.0/24 dev-infra-net`（网段固定，网关固定 IP 与 BaaS 信任代理白名单依赖该网段；`--ip-range` 把动态分配池与网关的 `172.28.0.10` 隔开）。
  - **公共栈搭建**：完整步骤（compose 定义、Nacos 配置准备）见 [README.md](README.md) 的「公共基础设施栈（前置）」一节。
  - **数据库初始化脚本**：`db/ai_work.sql`（业务库）、`db/ai_work_config.sql`（Nacos 配置中心库）、`db/ai_work_baas.sql`（BaaS 元数据库）；增量迁移脚本见 `db/migration/` 与 `db/ai_work_baas_plan_*_migration.sql`。
- 启动业务栈前须准备根目录 `.env`：`cp .env.example .env` 后填写。compose 会自动读取该文件，各变量用途与约束见文件内注释。`.env` 已被 gitignore，**严禁提交真实密钥**。
  - `BAAS_MASTER_KEYS` 为必填项，缺失时 BaaS 模块 fail-fast 拒绝启动，`docker compose config` 亦会直接报错。
  - **BaaS 主密钥只能来自环境变量或部署 Secret，不得入库、不得放入 Nacos 配置中心、不得提交 Git**（spec §12.1）。Nacos 配置数据与 BaaS 加密数据同存于一个 MySQL 实例，主密钥若置于其中则加密形同虚设。
  - `AI_WORK_LOG_ROOT` 未配置时**不会报错**，各服务日志静默落到仓库内 `./docker-data/logs`，会被 `git clean -xfd` 一并清除。
  - 变量取值一律使用纯字母数字：`#` 会被 dotenv 当作行内注释截断，曾导致 AES 密钥被截短、登录必然失败且难以定位。

## 构建、测试与开发命令

后端（项目根目录）：

- `mvn clean install -T 4 -Pcloud` 编译微服务版（`cloud` 为默认激活 profile）。
- `mvn clean install -T 4 -Pboot` 编译单体版（`ai-work-boot` 仅在 `boot` profile 下参与构建）。
- `mvn test` 运行后端单元测试；`mvn -pl ai-work-baas test` 只跑单个模块（`ai-work-baas` 占 98 个测试类，改动该模块必跑）。首次或依赖模块未 install 过时须加 `-am` 一并构建上游模块，否则依赖解析失败。
- `mvn spring-javaformat:apply` 按 Spring 规则格式化 Java 代码（pom 已内置 `spring-javaformat-maven-plugin`）。
- 改动任意 `pom.xml` 后先在本地跑 CI 同款校验：`python3 scripts/check-pom-duplicate-properties.py` 与 `mvn -B -ntp validate`（Enforcer 规则 `banDuplicatePomDependencyVersions` 禁止同一 pom 内重复声明依赖版本），否则会被 [.github/workflows/pom-checks.yml](.github/workflows/pom-checks.yml) 拦下。
- 启动前先起公共基础设施栈：`docker compose -f ~/docker-data/infra/docker-compose.yml up -d`（提供 MySQL 3306、Redis、Nacos 8848/9848 与控制台 18080）。
- `docker compose build && docker compose up` 构建镜像并启动微服务栈（仅 7 个业务服务；`ai-work-gateway` 统一入口宿主机 19999，`ai-work-monitor` 宿主机 15001；容器内端口仍为 9999 / 5001，宿主机侧改映射是为避开本机其他项目占用的同名端口）。单体形态使用 `docker compose -f docker-compose-boot.yml up`。

前端（`ai-work-ui` 目录内）：

- `npm run dev` 启动 Vite 开发服务器。
- `npm run build` 执行类型检查（vue-tsc）并构建生产包。
- `npm run test:unit` 运行 Vitest 单元测试（本地为 watch 模式，单次运行加 `-- run`；测试文件位于 `src/**/__tests__/*.spec.ts`）。
- `npm run lint` / `npm run format` 执行 lint（oxlint + ESLint）与格式化（Prettier）。
- 变更依赖后须在干净环境执行 `npm ci` 验证：macOS 上增量 `npm install` 会裁剪 `package-lock.json` 中跨平台 optional 依赖条目，导致 Linux/CI 构建失败。

## 团队开发规范（必须遵守）

本项目为多语言项目，开发规范按语言划分存放于 `context/team/coding/` 目录，总索引见 [context/team/coding/README.md](context/team/coding/README.md)。编写或修改代码前，先查阅对应语言目录下的规范：

- **Java**（各 `ai-work-*` 后端模块）→ `context/team/coding/java/`，含编程规约：命名、常量、格式、OOP、日期时间、集合、并发、控制语句、注释、前后端交互、其他（`01`~`11`）；错误码、异常与日志（`12`~`14`）；单元测试（`15`）；安全（`16`）；工程结构（`17`~`19`）；设计规约（`20`）；专有名词附录（`21`）
- **MySQL**（`db/` 及各模块 mapper，建表 / 索引 / SQL / ORM）→ `context/team/coding/mysql/`
- **前端**（`ai-work-ui`，Vue 3 + TypeScript）→ `context/team/coding/frontend/`，含通用、HTML、CSS、JavaScript、TypeScript 编码规约（`01`~`05`）与 Vue 组件规约（`06`）；React、Node.js 规约（`07`~`08`）本项目未使用，供其他技术栈项目复用。UI 视觉设计（配色、排版、间距、组件样式）遵循 [ai-work-ui/DESIGN.md](ai-work-ui/DESIGN.md)，编写或修改页面前先阅读该文件

与语言无关的工程协作规范（Git 提交、更新日志、Pull Request）见 `context/team/engineering/`，索引见 [context/team/engineering/README.md](context/team/engineering/README.md)。

其中标注【强制】的条目不允许违反；【推荐】条目除非有充分理由并在代码评审中说明，否则应遵守。

## 本仓库协作约定

- 设计文档与实施计划的入库位置是 `context/specs/` 与 `context/plans/`。**`docs/` 已被 gitignore**，写到 `docs/plans/`、`docs/superpowers/` 下的产物不会进版本库。
- `.claude/settings.json` 与 `.claude/hooks/check-protected-branch.sh` 随仓库分发，注册了 PreToolUse hook：在 `develop` / `main` / `master` 分支上，对 Edit / Write / NotebookEdit 一律 deny。这是刻意的护栏，遇到拒绝应切 feature 分支再改，**不要改用 shell 命令绕过**。脚本依赖 `jq`，本机未安装时 hook 会静默放行（不阻塞开发，但护栏失效）。`.claude/` 下的其他文件（`settings.local.json`、`worktrees/` 等）属本机私有，仍被 gitignore。

## 提交与 Pull Request

分支模型：`develop` 为日常集成分支，`main` 用于发版；`develop` 为保护分支，禁止直接提交，改动一律经 feature 分支合入。Commit Message、工作流、分支与 Tag 命名遵循团队 Git 规约：[context/team/engineering/01-git.md](context/team/engineering/01-git.md)（`type(scope): summary` 格式，如 `fix(upms): 清理登录失败缓存`）。

PR 描述必须按 [.github/PULL_REQUEST_TEMPLATE.md](.github/PULL_REQUEST_TEMPLATE.md) 的结构填写（规约见 [context/team/engineering/03-pull-request.md](context/team/engineering/03-pull-request.md)）；用 `gh pr create --body` 等方式创建 PR 时模板不会自动应用，须主动按模板结构组织描述。不提交生成产物。
