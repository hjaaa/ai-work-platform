# 仓库指南

## 项目结构与模块组织

`ai-work-platform` 通过根 `pom.xml` 聚合各 Spring Cloud 服务。运行时服务位于独立目录：`ai-work-register`（Nacos 注册中心）、`ai-work-gateway`（边缘路由）、`ai-work-auth`（授权）、`ai-work-upms`（用户与权限）、`ai-work-boot`（单体启动器）、`ai-work-visual`（监控、代码生成、quartz）。共享库与 DTO 位于 `ai-work-common`。前端应用位于 `ai-work-ui`（Vue 3 + TypeScript + Vite）。示例 SQL 与 Docker 构建上下文在 `db/`，基础设施编排见 `docker-compose.yml`。所有模块均采用标准的 `src/main/java` 与 `src/test/java` 布局。

开源版有意不包含 workflow、app server、MP、支付、报表、BI、多租户、数据权限（data-scope）与动态网关路由管理相关代码。网关路由通过常规配置文件维护。

## 构建、测试与开发命令

- 在项目根目录执行 `mvn clean install -T 4 -Pcloud`，基于托管 BOM 编译完整 cloud 版本。
- `docker compose build && docker compose up` 构建镜像并启动本地服务栈。

## 测试规范

使用 `spring-boot-starter-test`（JUnit 5、AssertJ、Mockito）编写单元测试与切片测试。测试类命名为 `*Tests.java`，fixture 放在同模块的 `src/test/resources`。覆盖重点：认证、网关过滤器、用户/权限逻辑、定时任务与代码生成。提交 PR 前运行 `mvn verify` 以执行完整插件链。

## 团队开发规范（必须遵守）

本项目为多语言项目，开发规范按语言划分存放于 `context/team/coding/` 目录，总索引见 [context/team/coding/README.md](context/team/coding/README.md)。编写或修改代码前，先查阅对应语言目录下的规范：

- **Java**（各 `ai-work-*` 后端模块）→ `context/team/coding/java/`，含编程规约：命名、常量、格式、OOP、日期时间、集合、并发、控制语句、注释、前后端交互、其他（`01`~`11`）；错误码、异常与日志（`12`~`14`）；单元测试（`15`）；安全（`16`）；工程结构（`17`~`19`）；设计规约（`20`）；专有名词附录（`21`）
- **MySQL**（`db/` 及各模块 mapper，建表 / 索引 / SQL / ORM）→ `context/team/coding/mysql/`
- **前端**（`ai-work-ui`，Vue 3 + TypeScript）→ `context/team/coding/frontend/`，含通用、HTML、CSS、JavaScript、TypeScript 编码规约（`01`~`05`）与 Vue 组件规约（`06`）；React、Node.js 规约（`07`~`08`）本项目未使用，供其他技术栈项目复用

与语言无关的工程协作规范（Git 提交、更新日志）见 `context/team/engineering/`，索引见 [context/team/engineering/README.md](context/team/engineering/README.md)。

其中标注【强制】的条目不允许违反；【推荐】条目除非有充分理由并在代码评审中说明，否则应遵守。

## 提交与 Pull Request

Commit Message、工作流、分支与 Tag 命名遵循团队 Git 规约：[context/team/engineering/01-git.md](context/team/engineering/01-git.md)（`type(scope): summary` 格式，如 `fix(upms): 清理登录失败缓存`）。

PR 要求：描述影响范围、列出受影响模块、关联相关 issue；UI 或 OpenAPI 响应有变化时附 curl/Postman 示例或截图；不提交生成产物；显式说明表结构 / 配置变更。