# ==================== 阶段 1: Node 构建 ====================
FROM node:20-alpine AS builder

WORKDIR /build

COPY package.json package-lock.json ./
RUN npm install

COPY . .
RUN npm run build

# ==================== 阶段 2: Nginx 托管 ====================
FROM nginx:1.27-alpine

# 复制构建产物
COPY --from=builder /build/dist /usr/share/nginx/html

# 复制 Nginx 配置
COPY nginx/default.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
