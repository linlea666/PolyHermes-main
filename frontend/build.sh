#!/bin/bash

# PolyHermes 前端构建脚本
# 支持通过环境变量配置后端 URL（用于跨域场景）
# 默认使用相对路径，通过反向代理转发到后端

set -e

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 打印信息
info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 检查 Node.js 环境
check_node() {
    if ! command -v node &> /dev/null; then
        error "Node.js 未安装，请先安装 Node.js 18+"
        exit 1
    fi
    
    NODE_VERSION=$(node -v | cut -d'v' -f2 | cut -d'.' -f1)
    if [ "$NODE_VERSION" -lt 18 ]; then
        error "Node.js 版本过低，需要 Node.js 18+，当前版本: $(node -v)"
        exit 1
    fi
    
    info "Node.js 环境检查通过: $(node -v)"
}

# 创建环境配置文件（可选）
create_env_file() {
    # 如果设置了环境变量，创建 .env.production 文件
    if [ -n "$VITE_API_URL" ] || [ -n "$VITE_WS_URL" ]; then
        info "检测到环境变量，创建 .env.production 文件..."
        
        # 解析 API URL，提取协议、主机和端口
        if [ -n "$VITE_API_URL" ]; then
            API_URL="$VITE_API_URL"
            if [[ $API_URL == http* ]]; then
                PROTOCOL=$(echo $API_URL | sed -E 's|^([^:]+)://.*|\1|')
                if [ "$PROTOCOL" = "https" ]; then
                    WS_PROTOCOL="wss"
                else
                    WS_PROTOCOL="ws"
                fi
                HOST_PORT=$(echo $API_URL | sed -E 's|^[^:]+://([^/]+).*|\1|')
                WS_URL="${WS_PROTOCOL}://${HOST_PORT}"
            else
                error "VITE_API_URL 格式错误，应为 http://host:port 或 https://host:port"
                exit 1
            fi
        else
            # 如果只设置了 WS_URL，从 WS_URL 推导 API_URL
            if [ -n "$VITE_WS_URL" ]; then
                WS_URL="$VITE_WS_URL"
                if [[ $WS_URL == ws* ]]; then
                    WS_PROTOCOL=$(echo $WS_URL | sed -E 's|^([^:]+)://.*|\1|')
                    if [ "$WS_PROTOCOL" = "wss" ]; then
                        API_PROTOCOL="https"
                    else
                        API_PROTOCOL="http"
                    fi
                    HOST_PORT=$(echo $WS_URL | sed -E 's|^[^:]+://([^/]+).*|\1|')
                    API_URL="${API_PROTOCOL}://${HOST_PORT}"
                else
                    error "VITE_WS_URL 格式错误，应为 ws://host:port 或 wss://host:port"
                    exit 1
                fi
            fi
        fi
        
        # 如果同时设置了两个环境变量，使用设置的值
        if [ -n "$VITE_WS_URL" ]; then
            WS_URL="$VITE_WS_URL"
        fi
        
        cat > .env.production <<EOF
# 后端 API 地址（可选，用于跨域场景）
# 如果未设置，前端将使用相对路径 /api（通过反向代理转发）
VITE_API_URL=${VITE_API_URL:-}
VITE_WS_URL=${VITE_WS_URL:-${WS_URL:-}}
EOF
        
        info "环境配置已创建: .env.production"
        if [ -n "$VITE_API_URL" ]; then
            info "  API URL: $VITE_API_URL"
        fi
        if [ -n "$VITE_WS_URL" ] || [ -n "$WS_URL" ]; then
            info "  WebSocket URL: ${VITE_WS_URL:-$WS_URL}"
        fi
    else
        info "未设置环境变量，前端将使用相对路径（通过反向代理转发）"
    fi
}

# 构建应用
build_app() {
    info "开始构建前端应用..."
    
    # 检查依赖
    if [ ! -d "node_modules" ]; then
        info "安装依赖..."
        npm install
    fi
    
    # 构建
    info "执行构建..."
    npm run build
    
    if [ ! -d "dist" ]; then
        error "构建失败，dist 目录不存在"
        exit 1
    fi
    
    info "构建完成: dist/"
    info "构建产物大小: $(du -sh dist | cut -f1)"
}

# 主函数
main() {
    echo "=========================================="
    echo "  PolyHermes 前端构建脚本"
    echo "=========================================="
    echo ""
    
    check_node
    
    # 解析参数
    if [ "$1" = "--api-url" ] && [ -n "$2" ]; then
        export VITE_API_URL="$2"
        info "使用自定义 API 地址: $VITE_API_URL"
    elif [ "$1" = "--ws-url" ] && [ -n "$2" ]; then
        export VITE_WS_URL="$2"
        info "使用自定义 WebSocket 地址: $VITE_WS_URL"
    fi
    
    create_env_file
    
    if [ -z "$VITE_API_URL" ] && [ -z "$VITE_WS_URL" ]; then
        info "前端将使用相对路径 /api 和 /ws"
        info "请确保在生产环境配置反向代理（Nginx/Apache）"
    fi
    
    build_app
    
    echo ""
    info "构建完成！"
    info "部署方式："
    if [ -n "$VITE_API_URL" ] || [ -n "$VITE_WS_URL" ]; then
        info "  已配置后端地址，前端将直接请求后端服务器"
    else
        info "  1. 静态文件服务器：将 dist/ 目录部署到 Nginx、Apache 等"
        info "  2. 配置反向代理：/api -> 后端地址，/ws -> 后端 WebSocket 地址"
    fi
    info "  3. 使用 serve 预览：npx serve -s dist -l 3000"
    echo ""
    info "提示：可通过环境变量配置后端地址："
    info "  VITE_API_URL=http://your-backend.com:8000 ./build.sh"
    info "  或使用参数：./build.sh --api-url http://your-backend.com:8000"
}

main "$@"

