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
npm run dev      # 开发（http://localhost:5173，/api 代理到网关 :9999）
npm run build    # 生产构建
npm run lint     # 代码检查
```

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
