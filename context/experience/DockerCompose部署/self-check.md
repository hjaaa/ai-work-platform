## Docker Compose 部署自检清单

- [ ] Maven 多模块 Dockerfile 先单独 COPY 各模块 pom.xml → `dependency:go-offline`，再 COPY src，利用 Docker 缓存层避免每次改代码重下依赖
- [ ] Vue Router history 模式部署到 Nginx 必须配置 `try_files $uri $uri/ /index.html`，否则刷新非首页路由会 404
- [ ] docker-compose 中服务间通信用服务名（如 `http://backend:8080`），不能用 `localhost`（每个容器有独立网络栈）
- [ ] 容器访问宿主机的 MySQL/Redis 用 `host.docker.internal`，不能用 `localhost`
- [ ] 删除源码文件后必须 `mvn clean package`，不能只 `mvn package`（增量构建不清理 target 中的旧文件）
