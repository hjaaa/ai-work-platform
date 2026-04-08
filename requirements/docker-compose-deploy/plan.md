# Implementation Plan: Docker Compose 部署

## Steps

1. 创建 Backend Dockerfile（多阶段构建：Maven build → JRE runtime）
2. 创建 Frontend Nginx 配置 + Dockerfile（Node build → Nginx serve）
3. 创建 docker-compose.yml（backend + frontend，外部 MySQL/Redis）
4. 创建 .env.example（环境变量模板）
5. 创建 deploy.sh（一键构建+启动）
6. 更新 .gitignore
