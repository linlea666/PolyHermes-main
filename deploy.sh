#!/bin/bash

# PolyHermes 一体化部署脚本
# 将前后端一起部署到一个 Docker 容器中

set -e

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color
COMPOSE_FILES=(-f docker-compose.yml)
COMPOSE_COMMAND=()
DEPLOY_MODE="full-build"
DEFAULT_IMAGE="wrbug/polyhermes:latest"
REPO_URL="https://github.com/linlea666/PolyHermes-main"

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

# 从环境变量或 .env 文件读取配置值，避免 source .env 时被特殊字符影响
get_config_value() {
    local key="$1"
    local value="${!key}"
    
    if [ -n "$value" ]; then
        echo "$value"
        return
    fi
    
    if [ -f ".env" ]; then
        grep "^${key}=" .env 2>/dev/null | cut -d'=' -f2- | sed 's/^["'\'']//;s/["'\'']$//' | tr -d '\r' || true
    fi
}

# 根据数据库配置选择 compose 文件
configure_compose_files() {
    local db_url
    db_url=$(get_config_value "DB_URL")
    
    COMPOSE_FILES=(-f docker-compose.yml)
    
    if [ "$DEPLOY_MODE" = "pull-image" ]; then
        if [[ "$db_url" == *"host.docker.internal"* ]]; then
            COMPOSE_FILES=(-f docker-compose.host-mysql.prod.yml)
            info "检测到 DB_URL 指向宿主机 MySQL，将使用 docker-compose.host-mysql.prod.yml（远程镜像）"
        else
            COMPOSE_FILES=(-f docker-compose.prod.yml)
            info "使用 docker-compose.prod.yml（远程镜像，包含内置 MySQL 服务）"
        fi
    elif [[ "$db_url" == *"host.docker.internal"* ]]; then
        COMPOSE_FILES=(-f docker-compose.host-mysql.yml)
        info "检测到 DB_URL 指向宿主机 MySQL，将使用 docker-compose.host-mysql.yml（本地构建）"
    else
        info "使用默认 docker-compose.yml（本地构建，包含内置 MySQL 服务）"
    fi
}

compose() {
    "${COMPOSE_COMMAND[@]}" "${COMPOSE_FILES[@]}" "$@"
}

get_image_ref() {
    local image
    image=$(get_config_value "POLYHERMES_IMAGE")
    echo "${image:-$DEFAULT_IMAGE}"
}

# 检查 Docker 环境
check_docker() {
    if ! command -v docker &> /dev/null; then
        error "Docker 未安装，请先安装 Docker"
        exit 1
    fi
    
    if docker compose version &> /dev/null; then
        COMPOSE_COMMAND=(docker compose)
    elif command -v docker-compose &> /dev/null; then
        COMPOSE_COMMAND=(docker-compose)
    else
        error "Docker Compose 未安装，请先安装 Docker Compose"
        exit 1
    fi
    
    info "Docker 环境检查通过"
}

# 生成随机字符串
generate_random_string() {
    local length=${1:-32}
    openssl rand -hex $length 2>/dev/null || \
    cat /dev/urandom | tr -dc 'a-zA-Z0-9' | fold -w $length | head -n 1
}

# 创建 .env 文件（如果不存在）
create_env_file() {
    if [ ! -f ".env" ]; then
        warn ".env 文件不存在，创建示例文件..."
        
        # 生成随机值
        DB_PASSWORD=$(generate_random_string 32)
        JWT_SECRET=$(generate_random_string 64)
        ADMIN_RESET_KEY=$(generate_random_string 32)
        
        cat > .env <<EOF
# 数据库配置
DB_URL=jdbc:mysql://mysql:3306/polyhermes?useSSL=false&serverTimezone=UTC&characterEncoding=utf8&allowPublicKeyRetrieval=true
DB_USERNAME=root
DB_PASSWORD=${DB_PASSWORD}

# Spring Profile
SPRING_PROFILES_ACTIVE=prod

# 服务器端口（对外暴露的端口）
SERVER_PORT=80

# MySQL 端口（可选，用于外部连接，默认 3307 避免与本地 MySQL 冲突）
MYSQL_PORT=3307

# JWT 密钥（已自动生成随机值，生产环境建议修改）
JWT_SECRET=${JWT_SECRET}

# 管理员密码重置密钥（已自动生成随机值，生产环境建议修改）
ADMIN_RESET_PASSWORD_KEY=${ADMIN_RESET_KEY}

# 日志级别配置（可选，默认值：root=WARN, app=INFO）
# 可选值：TRACE, DEBUG, INFO, WARN, ERROR, OFF
# LOG_LEVEL_ROOT=WARN
# LOG_LEVEL_APP=INFO

# 部署构建配置（可选）
# BUILD_IN_DOCKER=false
# FRONTEND_BUILD_MEM_MB=1024
# FRONTEND_BUILD_CONTAINER_MEM_MB=1536
# POLYHERMES_IMAGE=wrbug/polyhermes:latest
EOF
        info ".env 文件已创建，已自动生成随机密码和密钥"
        warn "生产环境建议修改以下参数："
        warn "  - DB_PASSWORD: 数据库密码（当前: ${DB_PASSWORD:0:8}...）"
        warn "  - JWT_SECRET: JWT 密钥（当前: ${JWT_SECRET:0:8}...）"
        warn "  - ADMIN_RESET_PASSWORD_KEY: 管理员密码重置密钥（当前: ${ADMIN_RESET_KEY:0:8}...）"
        exit 1
    fi
}

# 检查安全配置
check_security_config() {
    # 默认值常量
    DEFAULT_JWT_SECRET="change-me-in-production"
    DEFAULT_ADMIN_RESET_KEY="change-me-in-production"
    
    # 从 .env 文件读取配置（如果存在）
    local jwt_secret=""
    local admin_reset_key=""
    
    if [ -f ".env" ]; then
        # 从 .env 文件读取（使用 grep 和 sed 避免 source 可能的问题）
        jwt_secret=$(grep "^JWT_SECRET=" .env 2>/dev/null | cut -d'=' -f2- | sed 's/^"//;s/"$//' || echo "")
        admin_reset_key=$(grep "^ADMIN_RESET_PASSWORD_KEY=" .env 2>/dev/null | cut -d'=' -f2- | sed 's/^"//;s/"$//' || echo "")
    fi
    
    # 如果环境变量已设置，优先使用环境变量
    if [ -n "$JWT_SECRET" ]; then
        jwt_secret="$JWT_SECRET"
    fi
    if [ -n "$ADMIN_RESET_PASSWORD_KEY" ]; then
        admin_reset_key="$ADMIN_RESET_PASSWORD_KEY"
    fi
    
    local errors=0
    
    # 检查 JWT_SECRET
    if [ -z "$jwt_secret" ] || [ "$jwt_secret" = "$DEFAULT_JWT_SECRET" ]; then
        error "JWT_SECRET 不能使用默认值 '${DEFAULT_JWT_SECRET}'"
        error "请在 .env 文件中设置 JWT_SECRET 为安全的随机字符串"
        errors=$((errors + 1))
    fi
    
    # 检查 ADMIN_RESET_PASSWORD_KEY
    if [ -z "$admin_reset_key" ] || [ "$admin_reset_key" = "$DEFAULT_ADMIN_RESET_KEY" ]; then
        error "ADMIN_RESET_PASSWORD_KEY 不能使用默认值 '${DEFAULT_ADMIN_RESET_KEY}'"
        error "请在 .env 文件中设置 ADMIN_RESET_PASSWORD_KEY 为安全的随机字符串"
        errors=$((errors + 1))
    fi
    
    if [ $errors -gt 0 ]; then
        echo ""
        error "安全配置检查失败，部署已取消"
        echo ""
        info "提示：可以使用以下命令生成随机密钥："
        info "  openssl rand -hex 32  # 生成 32 字节的随机字符串（用于 ADMIN_RESET_PASSWORD_KEY）"
        info "  openssl rand -hex 64  # 生成 64 字节的随机字符串（用于 JWT_SECRET）"
        exit 1
    fi
    
    info "安全配置检查通过"
}

# 计算宿主机编译进程的低优先级前缀（nice/ionice），降低对实盘 JVM 的 CPU/IO 抢占。
# nice/ionice 作用于 docker 客户端进程有限，配合 docker run 的 --cpu-shares 才真正在 CPU 争用时让出。
BUILD_PRIORITY_PREFIX=""
compute_build_priority_prefix() {
    local prefix=""
    if command -v nice >/dev/null 2>&1; then prefix="nice -n 10"; fi
    if command -v ionice >/dev/null 2>&1; then prefix="$prefix ionice -c2 -n7"; fi
    BUILD_PRIORITY_PREFIX="$prefix"
}

# 在宿主机预编译前后端产物（不在镜像内编译）
# 使用 gradle / node 官方镜像 + 持久化缓存做增量编译，避免镜像阶段 gradle --no-daemon 全量冷编译导致 :compileKotlin 卡死/OOM
build_artifacts_on_host() {
    info "在宿主机预编译产物（BUILD_IN_DOCKER=false，镜像阶段不再编译）..."
    compute_build_priority_prefix
    if [ -n "$BUILD_PRIORITY_PREFIX" ]; then
        info "编译进程降优先级前缀: ${BUILD_PRIORITY_PREFIX}（并对构建容器设 --cpu-shares 512，CPU 争用时优先保实盘）"
    fi

    # 后端：用 gradle 容器编译 JAR，挂载缓存卷复用依赖与增量编译结果
    info "编译后端 JAR（增量编译，缓存复用）..."
    $BUILD_PRIORITY_PREFIX docker run --rm \
        --user root \
        --cpu-shares 512 \
        --memory 1536m \
        --memory-swap 1536m \
        -e GRADLE_USER_HOME=/gradle-cache \
        -e GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8 -Dorg.gradle.workers.max=1" \
        -v "$(pwd)/backend":/app/backend \
        -v polyhermes-gradle-cache:/gradle-cache \
        -w /app/backend \
        gradle:8.5-jdk17 \
        gradle bootJar --no-daemon -x test -Dorg.gradle.workers.max=1

    if [ -z "$(ls -A backend/build/libs/*.jar 2>/dev/null)" ]; then
        error "后端编译失败：未在 backend/build/libs/ 生成 JAR"
        exit 1
    fi
    info "后端 JAR 编译完成"

    # 前端：用 node 容器编译，挂载 npm 缓存卷
    # 显式限内存：vite/rollup 在低配机上 rendering chunks 容易吃满内存被 OOM kill 后表现为"卡住"。
    # NODE_OPTIONS 控制 V8 堆，--memory 限容器。容器上限必须高于 V8 堆，给 npm/vite/native 进程留余量。
    local fe_mem_mb
    local fe_container_mem_mb
    fe_mem_mb=$(get_config_value "FRONTEND_BUILD_MEM_MB")
    fe_mem_mb="${fe_mem_mb:-1024}"
    fe_container_mem_mb=$(get_config_value "FRONTEND_BUILD_CONTAINER_MEM_MB")
    fe_container_mem_mb="${fe_container_mem_mb:-1536}"
    if [ "$fe_container_mem_mb" -lt "$fe_mem_mb" ]; then
        warn "FRONTEND_BUILD_CONTAINER_MEM_MB(${fe_container_mem_mb}) 小于 FRONTEND_BUILD_MEM_MB(${fe_mem_mb})，自动提升到 ${fe_mem_mb}"
        fe_container_mem_mb="$fe_mem_mb"
    fi
    info "编译前端产物（NODE_OPTIONS 堆上限=${fe_mem_mb}MB，容器内存=${fe_container_mem_mb}MB）..."
    $BUILD_PRIORITY_PREFIX docker run --rm \
        --user root \
        --cpu-shares 512 \
        --memory "${fe_container_mem_mb}m" \
        --memory-swap "${fe_container_mem_mb}m" \
        -e VERSION="${DOCKER_VERSION}" \
        -e GIT_TAG="${DOCKER_VERSION}" \
        -e GITHUB_REPO_URL="${REPO_URL}" \
        -e NODE_OPTIONS="--max-old-space-size=${fe_mem_mb}" \
        -e npm_config_cache=/npm-cache \
        -v "$(pwd)/frontend":/app/frontend \
        -v polyhermes-npm-cache:/npm-cache \
        -w /app/frontend \
        node:18-alpine \
        sh -c '
            set -e
            if [ -f package-lock.json ]; then
                lock_hash="$(sha256sum package-lock.json | awk "{print \$1}")"
                saved_hash=""
                if [ -f node_modules/.package-lock.sha256 ]; then
                    saved_hash="$(cat node_modules/.package-lock.sha256)"
                fi
                if [ "$lock_hash" = "$saved_hash" ] && [ -d node_modules ]; then
                    echo "package-lock.json 未变化，使用 npm install --prefer-offline"
                    npm install --prefer-offline --no-audit --no-fund
                else
                    echo "package-lock.json 已变化或 node_modules 不存在，执行 npm ci"
                    npm ci --prefer-offline --no-audit --no-fund
                    mkdir -p node_modules
                    echo "$lock_hash" > node_modules/.package-lock.sha256
                fi
            else
                npm install --prefer-offline --no-audit --no-fund
            fi
            npm run build
        '

    if [ ! -d "frontend/dist" ] || [ -z "$(ls -A frontend/dist 2>/dev/null)" ]; then
        error "前端编译失败：未在 frontend/dist 生成产物"
        exit 1
    fi
    info "前端产物编译完成"
}

# full-build 会在服务器上抢占 CPU/IO 编译；若正在跑实盘策略，先提示暂停或改走 CI 镜像。
# 非交互（CI/无 TTY）或显式 SKIP_TRADING_PAUSE_CONFIRM=true 时跳过。
confirm_full_build_when_trading() {
    if [ "${SKIP_TRADING_PAUSE_CONFIRM:-false}" = "true" ]; then
        return
    fi
    if [ ! -t 0 ]; then
        warn "非交互环境，跳过 full-build 实盘暂停确认（建议 CI 出镜像 + --pull-image）"
        return
    fi
    echo ""
    warn "即将执行 full-build：将在本机编译前后端，显著占用 CPU/IO。"
    warn "若本服务器正在运行实盘 crypto-tail 策略，强烈建议二选一："
    warn "  1) 先在前端关闭相关策略的自动下单（enabled=off）再继续；或"
    warn "  2) 改用 CI 构建镜像 + ./deploy.sh --pull-image（几乎不占本机编译 CPU，推荐）。"
    printf "确认已暂停实盘或愿意继续 full-build? [y/N] "
    read -r _ans
    case "$_ans" in
        [yY]|[yY][eE][sS]) info "继续 full-build。" ;;
        *) error "已取消 full-build。可改用 ./deploy.sh --pull-image"; exit 1 ;;
    esac
}

# 构建并启动
deploy() {
    # 检查安全配置
    check_security_config
    configure_compose_files

    if [ "$DEPLOY_MODE" = "restart" ]; then
        info "部署模式：restart（只重启现有容器，不编译、不构建镜像）"
        info "启动服务..."
        compose up -d --no-build
        show_status
        return
    fi

    if [ "$DEPLOY_MODE" = "pull-image" ]; then
        local image_ref
        image_ref=$(get_image_ref)
        export POLYHERMES_IMAGE="$image_ref"

        info "部署模式：pull-image（拉取远程镜像，不本地编译）"
        info "拉取镜像: ${image_ref}"
        docker pull "$image_ref"

        info "启动服务..."
        compose up -d
        show_status
        return
    fi

    # full-build：编译前提示实盘暂停（保护正在运行的实盘策略）
    confirm_full_build_when_trading

    # 版本号：优先环境变量 DOCKER_VERSION，其次 .env 中的 DOCKER_VERSION，否则用当前分支名
    if [ -z "${DOCKER_VERSION}" ] && [ -f ".env" ]; then
        DOCKER_VERSION=$(grep "^DOCKER_VERSION=" .env 2>/dev/null | cut -d'=' -f2- | sed 's/^["'\'']//;s/["'\'']$//' | tr -d '\r')
    fi
    if [ -z "${DOCKER_VERSION}" ]; then
        CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "dev")
        DOCKER_VERSION=$(echo "$CURRENT_BRANCH" | tr '/' '-')
    fi
    export DOCKER_VERSION
    
    # 编译方式：默认在宿主机预编译产物（BUILD_IN_DOCKER=false），避免镜像阶段 :compileKotlin 卡死/OOM。
    # 如需 Dockerfile 内完整编译，可显式 BUILD_IN_DOCKER=true ./deploy.sh --full-build。
    BUILD_IN_DOCKER=$(get_config_value "BUILD_IN_DOCKER")
    BUILD_IN_DOCKER="${BUILD_IN_DOCKER:-false}"
    export BUILD_IN_DOCKER
    
    export VERSION=${DOCKER_VERSION}
    export GIT_TAG=${DOCKER_VERSION}
    export GITHUB_REPO_URL=${REPO_URL}

    if [ "$BUILD_IN_DOCKER" = "true" ]; then
        export DOCKER_BUILD_TARGET=runtime-docker
        info "部署模式：full-build（Dockerfile 内编译，版本号: ${DOCKER_VERSION}）"
    else
        export DOCKER_BUILD_TARGET=runtime-external
        info "部署模式：full-build（deploy.sh 预编译，Dockerfile 只 COPY 产物，版本号: ${DOCKER_VERSION}）"
        build_artifacts_on_host
    fi

    compose build

    info "启动服务..."
    compose up -d

    show_status
}

show_status() {
    info "等待服务启动..."
    sleep 5
    
    info "检查服务状态..."
    compose ps
    
    info "查看日志: ${COMPOSE_COMMAND[*]} ${COMPOSE_FILES[*]} logs -f"
    info "停止服务: ${COMPOSE_COMMAND[*]} ${COMPOSE_FILES[*]} down"
}

usage() {
    cat <<EOF
用法: $0 [--restart|--full-build|--pull-image]

部署模式：
  --restart      只执行 docker compose up -d --no-build，不编译、不构建镜像
  --full-build   完整构建并启动（默认）。BUILD_IN_DOCKER=false 时脚本预编译；BUILD_IN_DOCKER=true 时 Dockerfile 内编译
  --pull-image   拉取远程镜像并启动，不本地编译。镜像默认 ${DEFAULT_IMAGE}，可用 POLYHERMES_IMAGE 覆盖

兼容参数：
  --use-docker-hub, -d  等同于 --pull-image

环境变量：
  SKIP_TRADING_PAUSE_CONFIRM=true  跳过 full-build 前的实盘暂停确认（CI/无 TTY 自动跳过）

实盘建议：
  服务器实盘运行期不建议 full-build（编译抢占 CPU 可能影响下单/退出时延）。
  推荐用 CI（.github/workflows 出镜像）构建并推送，服务器只执行 ./deploy.sh --pull-image。

示例：
  $0 --restart
  $0 --full-build
  BUILD_IN_DOCKER=true $0 --full-build
  POLYHERMES_IMAGE=wrbug/polyhermes:latest $0 --pull-image
EOF
}

# 主函数
main() {
    echo "=========================================="
    echo "  PolyHermes 一体化部署脚本"
    echo "=========================================="
    echo ""
    
    # 解析参数
    while [ $# -gt 0 ]; do
        case "$1" in
            --restart)
                DEPLOY_MODE="restart"
                ;;
            --full-build)
                DEPLOY_MODE="full-build"
                ;;
            --pull-image|--use-docker-hub|-d)
                DEPLOY_MODE="pull-image"
                ;;
            --help|-h)
                usage
                exit 0
                ;;
            *)
                error "未知参数: $1"
                usage
                exit 1
                ;;
        esac
        shift
    done

    info "当前部署模式: ${DEPLOY_MODE}"
    echo ""
    
    check_docker
    create_env_file
    deploy
    
    echo ""
    info "部署完成！"
    info "访问地址: http://localhost:${SERVER_PORT:-80}"
    echo ""
    if [ "$DEPLOY_MODE" = "full-build" ]; then
        if [ -z "${DOCKER_VERSION}" ] && [ -f ".env" ]; then
            DOCKER_VERSION=$(grep "^DOCKER_VERSION=" .env 2>/dev/null | cut -d'=' -f2- | sed 's/^["'\'']//;s/["'\'']$//' | tr -d '\r')
        fi
        if [ -z "${DOCKER_VERSION}" ]; then
            CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "dev")
            DOCKER_VERSION=$(echo "$CURRENT_BRANCH" | tr '/' '-')
        fi
        info "提示：本地构建的版本号: ${DOCKER_VERSION}（可在 .env 或环境变量中设置 DOCKER_VERSION）"
        info "仅重启现有容器: ./deploy.sh --restart"
        info "拉远程镜像部署: ./deploy.sh --pull-image"
    fi
}

main "$@"
