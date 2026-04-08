# Feature Specification: Docker Compose 部署

**Created**: 2026-04-07 | **Status**: Draft

## 需求

使用 docker-compose 部署项目，包含 Backend（Spring Boot）和 Frontend（Nginx）两个服务。
MySQL 和 Redis 使用外部实例，不纳入 compose。
支持本地开发和服务器部署两种场景。

## Requirements

- **FR-001**: Backend Dockerfile，基于 Maven 多阶段构建打包 Spring Boot JAR
- **FR-002**: Frontend 使用 Nginx 托管静态资源 + 反向代理 API 到 Backend
- **FR-003**: docker-compose.yml 编排 backend + frontend 两个服务
- **FR-004**: 部署脚本 deploy.sh（构建镜像+启动服务）
- **FR-005**: 环境变量通过 .env 文件注入（DB_PASSWORD, REDIS_PASSWORD 等）
