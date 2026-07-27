<p align="center">
 <img src="https://img.shields.io/badge/AI%20Work%20Platform-4.0-success.svg" alt="AI Work Platform">
 <img src="https://img.shields.io/badge/Spring%20Cloud-2025.1-blue.svg" alt="Spring Cloud">
 <img src="https://img.shields.io/badge/Spring%20Boot-4.0-blue.svg" alt="Spring Boot">
 <img src="https://img.shields.io/badge/Vue-3.5-blue.svg" alt="Vue">
</p>

## 系统说明

- AI Work Platform 是基于 Spring Cloud、Spring Boot、OAuth2 的 RBAC 企业级快速开发平台，同时支持微服务架构和单体架构。
- 认证中心基于 Spring Authorization Server 落地生产级 OAuth2 实践，支持授权码、密码、刷新令牌等常见登录与授权场景。
- 当前版本保留认证、网关、用户权限、监控、代码生成和定时任务等核心能力，移除了商业版中的多租户、数据权限、动态路由、流程、支付、公众号、报表和移动端服务等扩展模块。
- 提供 Docker Compose 本地编排，支持快速启动 MySQL、Redis、Nacos 和业务服务。

## 使用文档

本项目基于开源脚手架 pig4cloud 整体改名而来（Maven 坐标、Java 包名、模块目录均已迁移至 `ai-work` 命名体系）。原始脚手架的部署与开发文档可参考：[wiki.pig4cloud.com](https://wiki.pig4cloud.com)，涵盖开发环境配置、服务端启动、前端运行、微服务部署和单体部署等关键步骤。

## 快速开始

### 基础环境

- JDK 17+
- Maven 3.9+
- Docker 和 Docker Compose
- Node.js 16+（运行 `ai-work-ui` 前端时需要）

### 公共基础设施栈（前置）

MySQL、Redis、Nacos 由跨项目共享的公共基础设施栈提供（`~/docker-data/infra/docker-compose.yml`），不随本仓库编排。首次使用需一次性创建公共网络：

```bash
docker network create --driver bridge --subnet 172.28.0.0/16 dev-infra-net
```

> 网段必须是 `172.28.0.0/16`：网关钉死在 `172.28.0.10`，BaaS 的信任代理白名单依赖该地址。

之后每次启动业务栈前先起公共栈（提供 `dev-mysql` / `dev-redis` / `dev-nacos`）：

```bash
docker compose -f ~/docker-data/infra/docker-compose.yml up -d
```

### 微服务模式

在项目根目录执行完整编译，再构建并启动本地服务栈：

```bash
mvn clean install -T 4 -Pcloud
docker compose build && docker compose up
```

服务启动后，默认通过网关端口 `9999` 访问后端接口，Nacos 服务端端口为 `8848`、控制台为 `18080`。

### 单体模式

单体模式通过 `boot` profile 启用 `ai-work-boot` 模块：

```bash
mvn clean install -T 4 -Pboot
docker compose -f docker-compose-boot.yml build && docker compose -f docker-compose-boot.yml up
```

单体服务默认监听 `9999` 端口。

## 核心依赖

| 依赖 | 版本 |
| --- | --- |
| AI Work Platform | 4.0.0 |
| JDK | 17+ |
| Spring Boot | 4.0.6 |
| Spring Cloud | 2025.1.2 |
| Spring Cloud Alibaba | 2025.1.0.0 |
| Spring Security OAuth2 Authorization Server | 7.0.5 |
| MyBatis Plus | 3.5.16 |
| Nacos Client | 3.1.2 |
| Druid | 1.2.28 |
| Vue | 3.5.34 |
| Element Plus | 2.13.7 |
| Vite | 5.4.21 |

## 模块说明

```lua
ai-work-ui -- 前端项目（独立仓库）

ai-work-platform
├── ai-work-register -- Nacos Server [8848/9848/18080]（已由公共基础设施栈的 dev-nacos 提供，本模块保留作回退用）
├── ai-work-gateway -- Spring Cloud Gateway 网关 [9999]
├── ai-work-auth -- 授权服务 [3000]
├── ai-work-upms -- 通用用户权限管理模块
│   ├── ai-work-upms-api -- 通用用户权限管理公共 API
│   └── ai-work-upms-biz -- 通用用户权限业务服务 [4000]
├── ai-work-common -- 系统公共模块
│   ├── ai-work-common-bom -- 全局依赖版本管理
│   ├── ai-work-common-core -- 公共工具类核心包
│   ├── ai-work-common-data -- MyBatis Plus 与缓存扩展
│   ├── ai-work-common-datasource -- 动态数据源封装
│   ├── ai-work-common-log -- 日志服务
│   ├── ai-work-common-oss -- 文件上传工具类
│   ├── ai-work-common-security -- 安全工具类
│   ├── ai-work-common-sentinel -- Sentinel 与异常处理封装
│   ├── ai-work-common-swagger -- 接口文档封装
│   ├── ai-work-common-feign -- OpenFeign 扩展封装
│   ├── ai-work-common-excel -- Excel 导入导出封装
│   └── ai-work-common-xss -- XSS 安全封装
├── ai-work-visual -- 可视化支撑服务
│   ├── ai-work-monitor -- 服务监控 [5001]
│   ├── ai-work-codegen -- 图形化代码生成 [5002]
│   └── ai-work-quartz -- 定时任务管理台 [5007]
└── ai-work-boot -- 单体模式启动器 [9999]，通过 `-Pboot` 启用
```

## 配置说明

- 微服务模式使用 `cloud` profile，默认激活 `dev` 环境配置。
- 单体模式使用 `boot` profile，`ai-work-boot` 模块只在该 profile 下参与构建。
- 网关路由由 `ai-work-gateway/src/main/resources/application.yml` 和 Nacos 配置维护，不再依赖动态路由表。
- 默认数据库脚本位于 `db/`，业务表初始化到 `ai_work`，Nacos 配置初始化到 `ai_work_config`。
- 包名已统一为 `com.aiwork`。

## 开源共建

### 开源协议

本项目衍生自 pig4cloud 开源脚手架，遵循 [Apache 2.0 协议](https://www.apache.org/licenses/LICENSE-2.0.html)，允许商业使用，但务必保留类作者、Copyright 信息。

### 其他说明

1. 请基于当前开发分支提交变更，并写清楚改动动机。
2. 提交问题时请写清楚问题现象、开发环境和复现步骤。
3. 代码格式遵循 Spring Java Format，提交前可在项目根目录运行：

```bash
mvn spring-javaformat:apply
```
