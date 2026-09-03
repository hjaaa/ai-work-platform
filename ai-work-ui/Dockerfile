# 与后端镜像做法一致：只打包宿主机上已构建好的产物，不在镜像内执行 npm 构建
# （构建容器访问 npm 源极慢且不稳定，且前端开发本就要求宿主机具备 Node 环境）。
# 构建镜像前先在 ai-work-ui 目录执行 npm ci && npm run build 产出 dist/；
# 单体(boot)形态需以 VITE_AUTH_PATH=/admin VITE_BAAS_PATH=/admin npm run build 构建产物。
FROM nginx:alpine

ENV TZ=Asia/Shanghai

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY dist /usr/share/nginx/html

EXPOSE 80
