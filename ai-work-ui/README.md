# ai-work-ui

AI Work Platform 前端项目，基于 Vue 3 + TypeScript + Vite，参考 [pig-ui](https://github.com/pig-mesh/pig-ui) 的项目结构精简自建。

## 技术栈

- Vue 3 + TypeScript + Vite
- Vue Router / Pinia
- Element Plus / Tailwind CSS
- Axios（统一封装于 `src/utils/request.ts`）

## 环境要求

- Node >= 22.22.2（以 package.json 的 engines 字段为准）

## 快速开始

```bash
npm install
npm run dev      # 开发（http://localhost:5173，/api 代理到网关 :19999，即 compose 宿主机映射端口）
npm run build    # 生产构建
npm run lint     # 代码检查
```

## Docker 部署

仓库根目录的 `docker-compose.yml` / `docker-compose-boot.yml` 已包含 `ai-work-ui` 服务。与后端镜像一样，`Dockerfile` 只把宿主机构建好的 `dist/` 与 `nginx.conf` 打进 nginx 镜像，不在镜像内执行 npm 构建，因此 `docker compose build` 前先执行 `npm ci && npm run build`。`nginx.conf` 负责 history 路由回退并把 `/api` 反代到容器网络内的 `ai-work-gateway:9999`，宿主机访问 `http://localhost:18000`。单体形态构建产物时设置 `VITE_AUTH_PATH=/admin VITE_BAAS_PATH=/admin`。

## 目录约定

```
src/
├── api/        # 接口定义层，按业务域分文件
├── assets/     # 静态资源
├── components/ # 通用组件
├── layout/     # 全局布局
├── router/     # 路由（登录鉴权后接入动态路由）
├── stores/     # Pinia 状态
├── styles/     # 全局样式（Tailwind 入口）
├── utils/      # 工具（request.ts 等）
└── views/      # 页面，按业务域分目录
```

## 待办（后续迭代）

- 登录 / OAuth2 鉴权联调
- 动态菜单与权限路由
