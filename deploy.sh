#!/bin/bash
# AI Work Platform 部署脚本
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# 颜色输出
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; exit 1; }

# 自动检测 JDK 21
if [ -z "$JAVA_HOME" ] || ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"21\.'; then
    for candidate in \
        "$HOME/devsoft/jdk-21"*.jdk/Contents/Home \
        /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
        /Library/Java/JavaVirtualMachines/jdk-21*.jdk/Contents/Home; do
        if [ -d "$candidate" ]; then
            export JAVA_HOME="$candidate"
            break
        fi
    done
    if [ -z "$JAVA_HOME" ] || ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"21\.'; then
        error "未找到 JDK 21，请安装或设置 JAVA_HOME 环境变量"
    fi
    info "使用 JDK: $JAVA_HOME"
fi
export PATH="$JAVA_HOME/bin:$PATH"

# 检查 .env 文件
if [ ! -f .env ]; then
    warn ".env 文件不存在，从 .env.example 复制..."
    cp .env.example .env
    warn "请编辑 .env 配置后重新运行"
    exit 1
fi

case "${1:-up}" in
    up)
        info "Maven 构建 Backend JAR..."
        mvn package -DskipTests -B -q
        info "构建 Docker 镜像并启动服务..."
        docker compose build
        docker compose up -d
        info "服务已启动"
        docker compose ps
        ;;
    down)
        info "停止服务..."
        docker compose down
        info "服务已停止"
        ;;
    restart)
        info "重启服务..."
        docker compose down
        info "Maven 构建 Backend JAR..."
        mvn package -DskipTests -B -q
        info "构建 Docker 镜像并启动服务..."
        docker compose build
        docker compose up -d
        info "服务已重启"
        docker compose ps
        ;;
    logs)
        docker compose logs -f ${2:-}
        ;;
    status)
        docker compose ps
        ;;
    *)
        echo "用法: $0 {up|down|restart|logs|status}"
        echo ""
        echo "  up       构建并启动所有服务（默认）"
        echo "  down     停止所有服务"
        echo "  restart  重启所有服务"
        echo "  logs     查看日志（可指定服务名，如: $0 logs backend）"
        echo "  status   查看服务状态"
        exit 1
        ;;
esac
