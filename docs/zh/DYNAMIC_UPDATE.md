# PolyHermes 动态更新技术方案

## 1. 方案概述

### 1.1 核心目标

在不重启 Docker 容器的情况下，实现后端 JAR 和前端产物的动态更新。

### 1.2 关键设计

- **单一更新包**：前后端打包在一个 tar.gz 文件中
- **独立更新服务**：Python Flask 服务（端口 9090）专门负责更新
- **进程隔离**：更新服务与主应用分离，互不影响
- **自动回滚**：更新失败自动恢复到旧版本

---

## 2. 架构设计

### 2.1 整体架构

```
┌────────────────────────────────────────────────────────┐
│                  GitHub Releases                        │
│  Release v1.3.0                                         │
│  └── polyhermes-v1.3.0-update.tar.gz                    │
└──────────────────────┬─────────────────────────────────┘
                       │ HTTPS Download
                       ▼
┌─────────────────────────────────────────────────────────┐
│                    Docker 容器                           │
│                                                          │
│  ┌────────────────────────────────────────────────┐    │
│  │ Nginx (Port 80)                                 │    │
│  │  ┌──────────────────────────────────────────┐  │    │
│  │  │ /             → 前端静态文件              │  │    │
│  │  │ /api/         → http://localhost:8000     │  │    │
│  │  │ /api/update/  → http://localhost:9090 ←【新】│  │
│  │  └──────────────────────────────────────────┘  │    │
│  └──────┬───────────────────────────┬─────────────┘    │
│         │                           │                   │
│         │                           │                   │
│  ┌──────▼────────────┐    ┌────────▼──────────────┐   │
│  │ 后端应用 (8000)   │    │ 更新服务 (9090)       │   │
│  │  - 业务 API       │    │  - GET  /check        │   │
│  │  - 无更新功能     │←✅ │  - POST /update       │   │
│  └───────────────────┘    │  - GET  /status       │   │
│                            │  - GET  /logs         │   │
│                            │  - GET  /version      │   │
│                            └───────────────────────┘   │
└─────────────────────────────────────────────────────────┘
                      ▲
                      │ HTTP/HTTPS
                ┌─────┴─────┐
                │   用户    │
                └───────────┘
```

**关键设计**：
- ✅ **Nginx 直接代理** - `/api/update/*` 直接转发到 Python (9090)
- ✅ **后端无感知** - 不需要 SystemUpdateController
- ✅ **独立性强** - 后端崩溃不影响更新功能

### 2.2 进程架构

```
PID 1: start.sh
├── PID 10: python3 update-service.py (9090)  ← 更新服务
├── PID 20: java -jar app.jar (8000)          ← 主应用
└── PID 30: nginx -g "daemon off;" (80)       ← 代理 + 静态文件
```

**调用链路**：
```
用户请求 /api/update/check
  ↓
Nginx 接收 (80)
  ↓
匹配规则 location /api/update/
  ↓
代理转发 proxy_pass http://localhost:9090/
  ↓
Python 处理 GET /check
  ↓
返回 JSON { code: 0, data: {...} }
```

**关键**：Nginx 作为前台进程保持容器存活，Java 和 Python 可被重启。

---

## 3. 更新包结构

```
polyhermes-v1.3.0-update.tar.gz
├── backend/
│   └── polyhermes.jar
├── frontend/
│   ├── index.html
│   ├── assets/
│   └── ...
└── version.json
```

**version.json 格式**：
```json
{
  "version": "1.3.0",
  "tag": "v1.3.0",
  "buildTime": "2026-01-20T15:00:00Z",
  "releaseNotes": "## 新功能\n..."
}
```

---

## 4. GitHub Actions 配置

### 4.1 ⚠️ 重要：不创建新文件

**不要创建** `release-build.yml`，而是**直接修改现有的** `.github/workflows/docker-build.yml`。

**原因**：
- 现有的 `docker-build.yml` 已经监听 `release.published` 事件
- 创建新文件会导致两个 workflow 同时触发（冲突）
- 在一个 workflow 中统一管理更高效

### 4.2 编译优化策略

**关键优化**：前后端只编译一次，产物复用三次

```
编译流程:
  Steps 3-6: 编译产物
    ├── gradle bootJar         → backend/build/libs/*.jar
    └── npm run build          → frontend/dist/*
  
  复用1: Step 7
    └── Create Update Package  ← 复用编译产物
  
  复用2: Step 10
    └── Build Docker Image     ← 复用编译产物（不再编译）
  
  复用3: (可选)
    └── 缓存供后续构建使用
```

**时间节省**：
- 传统方式：编译2次 ~ 15分钟
- 优化后：编译1次 ~ 8分钟
- **节省约 7 分钟**

### 4.3 修改方案

在现有的 `docker-build.yml` 中增加以下步骤（**在构建 Docker 镜像之前**）：

#### **步骤1：增加权限声明**

```yaml
jobs:
  build-and-push:
    runs-on: ubuntu-latest
    
    permissions:
      contents: write  # 【新增】需要写权限以上传 Assets
```

#### **步骤2：构建后端 JAR（在 Docker 构建之前）**

```yaml
- name: Setup JDK 17
  uses: actions/setup-java@v4
  with:
    java-version: '17'
    distribution: 'temurin'

- name: Build Backend JAR
  run: |
    cd backend
    gradle bootJar --no-daemon
    echo "✅ 后端构建完成"
    ls -lh build/libs/*.jar
```

#### **步骤3：构建前端**

```yaml
- name: Setup Node.js
  uses: actions/setup-node@v4
  with:
    node-version: '18'

- name: Build Frontend
  run: |
    cd frontend
    npm ci
    npm run build
    echo "✅ 前端构建完成"
```

#### **步骤4：打包更新包**

```yaml
- name: Create Update Package
  run: |
    echo "📦 打包更新包..."
    
    mkdir -p update-package/backend update-package/frontend
    
    # 复制后端 JAR
    cp backend/build/libs/*.jar update-package/backend/polyhermes.jar
    
    # 复制前端产物
    cp -r frontend/dist/* update-package/frontend/
    
    # 创建版本信息
    cat > update-package/version.json <<EOF
    {
      "version": "${{ steps.extract_version.outputs.VERSION }}",
      "tag": "${{ steps.extract_version.outputs.TAG }}",
      "buildTime": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
      "releaseNotes": $(echo '${{ github.event.release.body }}' | jq -Rs .)
    }
    EOF
    
    # 打包
    cd update-package
    tar -czf ../polyhermes-${{ steps.extract_version.outputs.TAG }}-update.tar.gz .
    cd ..
    
    echo "✅ 打包完成"
    ls -lh polyhermes-*.tar.gz
```

#### **步骤5：计算校验和**

```yaml
- name: Calculate Checksum
  id: checksum
  run: |
    FILE="polyhermes-${{ steps.extract_version.outputs.TAG }}-update.tar.gz"
    CHECKSUM=$(sha256sum "$FILE" | awk '{print $1}')
    echo "CHECKSUM=$CHECKSUM" >> $GITHUB_OUTPUT
    echo "$CHECKSUM  $FILE" > checksums.txt
```

#### **步骤6：上传到 Release Assets**

```yaml
- name: Upload Update Package
  uses: actions/upload-release-asset@v1
  env:
    GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
  with:
    upload_url: ${{ github.event.release.upload_url }}
    asset_path: ./polyhermes-${{ steps.extract_version.outputs.TAG }}-update.tar.gz
    asset_name: polyhermes-${{ steps.extract_version.outputs.TAG }}-update.tar.gz
    asset_content_type: application/gzip

- name: Upload Checksums
  uses: actions/upload-release-asset@v1
  env:
    GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
  with:
    upload_url: ${{ github.event.release.upload_url }}
    asset_path: ./checksums.txt
    asset_name: checksums.txt
    asset_content_type: text/plain
```

### 4.3 完整的步骤顺序

```
现有步骤：
1. Checkout code
2. Extract version
3. Send Telegram (build started)

【新增步骤】：
4. Setup JDK 17
5. Build Backend JAR
6. Setup Node.js
7. Build Frontend
8. Create Update Package
9. Calculate Checksum
10. Upload Update Package
11. Upload Checksums

现有步骤（保持不变）：
12. Set up Docker Buildx
13. Log in to Docker Hub
14. Build and push Docker image
15. Send Telegram notification
```

**优势**：
- ✅ 后端和前端只编译一次（Docker 构建可以复用）
- ✅ 所有发布产物在一个 workflow 中完成
- ✅ 避免 workflow 冲突

### 4.4 可选优化：修改 Telegram 通知

在最后的通知步骤中，可以增加更新包信息：

```yaml
- name: Send Telegram notification
  # ...
  run: |
    MESSAGE="✅ <b>PolyHermes ${TAG} 发布成功</b>

📦 版本: ${VERSION}
🔗 <a href=\"${RELEASE_URL}\">查看 Release</a>

<b>已上传:</b>
- Docker 镜像: wrbug/polyhermes:${TAG}
- 更新包: polyhermes-${TAG}-update.tar.gz

<b>使用:</b>
- Docker 部署: docker pull wrbug/polyhermes:${TAG}
- 在线更新: 系统设置 → 系统更新"
    # ...发送消息
```

---

## 5. Docker 容器配置

### 5.1 编译策略（最佳实践）

**采用混合方案：条件编译**

```
编译策略:
  GitHub Actions (BUILD_IN_DOCKER=false):
    ├── Actions 编译产物           ← 编译1次
    └── Docker 跳过编译，复用产物   ← 不编译
  
  本地 deploy.sh (BUILD_IN_DOCKER=true):
    └── Docker 内部编译             ← 编译1次
```

**核心思路**：
- 通过 `BUILD_IN_DOCKER` 参数控制编译位置
- GitHub Actions：先编译，Docker 复用（快速）
- 本地开发：Docker 自动编译（方便）

**优势**：
- ✅ Actions 编译1次，节省约 5-7 分钟
- ✅ 本地 deploy.sh 完全兼容，零改动
- ✅ Docker 镜像可独立构建
- ✅ 灵活性最高

### 5.2 Dockerfile（混合方案）

```dockerfile
# 构建参数：控制是否在 Docker 内编译
# true  = Docker 内部编译（本地开发）
# false = 使用外部产物（GitHub Actions）
ARG BUILD_IN_DOCKER=true

# ==================== 阶段1：构建后端 ====================
FROM gradle:8.5-jdk17 AS backend-build
ARG BUILD_IN_DOCKER

WORKDIR /app/backend

# 复制构建配置
COPY backend/build.gradle.kts backend/settings.gradle.kts ./
COPY backend/gradle ./gradle

# 条件：仅在 Docker 内部编译时下载依赖
RUN if [ "$BUILD_IN_DOCKER" = "true" ]; then \
      gradle dependencies --no-daemon || true; \
    fi

# 复制源码
COPY backend/src ./src

# 条件：仅在 Docker 内部编译时执行构建
RUN if [ "$BUILD_IN_DOCKER" = "true" ]; then \
      echo "🔨 Docker 内部编译后端..."; \
      gradle bootJar --no-daemon; \
    else \
      echo "⏭️  跳过编译，使用外部产物"; \
    fi

# ==================== 阶段2：构建前端 ====================
FROM node:18-alpine AS frontend-build
ARG BUILD_IN_DOCKER

WORKDIR /app/frontend

# 复制 package.json
COPY frontend/package*.json ./

# 条件：仅在 Docker 内部编译时安装依赖
RUN if [ "$BUILD_IN_DOCKER" = "true" ]; then \
      npm ci; \
    fi

# 复制源码
COPY frontend/ ./

# 条件：仅在 Docker 内部编译时执行构建
RUN if [ "$BUILD_IN_DOCKER" = "true" ]; then \
      echo "🔨 Docker 内部编译前端..."; \
      npm run build; \
    else \
      echo "⏭️  跳过编译，使用外部产物"; \
    fi

# ==================== 阶段3：运行环境 ====================
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# 安装 Python 和依赖
RUN apt-get update && \
    apt-get install -y nginx curl tzdata jq python3 python3-pip && \
    pip3 install flask requests && \
    rm -rf /var/lib/apt/lists/* && \
    rm -rf /etc/nginx/sites-enabled/default

# 复制构建产物
# - 如果 BUILD_IN_DOCKER=true: 从构建阶段复制
# - 如果 BUILD_IN_DOCKER=false: 从 context 复制（外部产物）
COPY --from=backend-build /app/backend/build/libs/*.jar app.jar
COPY --from=frontend-build /app/frontend/dist /usr/share/nginx/html
COPY docker/nginx.conf /etc/nginx/nginx.conf

# 创建更新服务相关目录和脚本
RUN mkdir -p /app/updates /app/backups /var/log/polyhermes
COPY docker/update-service.py /app/update-service.py
COPY docker/start.sh /app/start.sh
RUN chmod +x /app/start.sh

# 记录初始版本（从构建参数）
ARG VERSION=dev
ARG GIT_TAG=dev
RUN echo "{\"version\":\"${VERSION}\",\"tag\":\"${GIT_TAG}\",\"buildTime\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}" > /app/version.json

# 设置目录权限
RUN useradd -m -u 1000 appuser && \
    mkdir -p /var/log/nginx /var/lib/nginx /var/cache/nginx /var/run && \
    chown -R appuser:appuser /app && \
    chown -R root:root /usr/share/nginx/html /var/log/nginx /var/lib/nginx /var/cache/nginx /etc/nginx /var/run

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -f http://localhost/api/system/health || exit 1

ENTRYPOINT ["/app/start.sh"]
```

**关键设计**：
1. `ARG BUILD_IN_DOCKER=true` - 默认在 Docker 内编译（本地开发友好）
2. 条件判断 `if [ "$BUILD_IN_DOCKER" = "true" ]` - 根据参数决定是否编译
3. `COPY --from=backend-build` - 无论如何都从构建阶段复制（统一路径）

### 5.3 GitHub Actions 使用方式

在 `.github/workflows/docker-build.yml` 中：

```yaml
steps:
  # 【先编译产物】
  - name: Setup JDK 17
    uses: actions/setup-java@v4
    with:
      java-version: '17'
      distribution: 'temurin'
  
  - name: Build Backend JAR
    run: |
      cd backend
      gradle bootJar --no-daemon
      echo "✅ 后端编译完成"
  
  - name: Setup Node.js
    uses: actions/setup-node@v4
    with:
      node-version: '18'
  
  - name: Build Frontend
    run: |
      cd frontend
      npm ci
      npm run build
      echo "✅ 前端编译完成"
  
  # 【打包更新包 - 复用产物】
  - name: Create Update Package
    run: |
      mkdir -p update-package/backend update-package/frontend
      cp backend/build/libs/*.jar update-package/backend/polyhermes.jar
      cp -r frontend/dist/* update-package/frontend/
      # ... 打包
  
  # 【构建 Docker - 跳过编译】
  - name: Build and push Docker image
    uses: docker/build-push-action@v5
    with:
      context: .
      file: ./Dockerfile
      push: true
      platforms: linux/amd64,linux/arm64
      tags: wrbug/polyhermes:${{ steps.version.outputs.TAG }}
      build-args: |
        BUILD_IN_DOCKER=false        ← 关键：不在 Docker 内编译
        VERSION=${{ steps.version.outputs.VERSION }}
        GIT_TAG=${{ steps.version.outputs.TAG }}
```

**流程**：
```
1. Actions 编译产物        → backend/build/libs/*.jar, frontend/dist/
2. 打包更新包（复用）      → polyhermes-v1.3.0-update.tar.gz
3. Docker 构建（跳过编译） → 直接 COPY 已编译的产物
```

**时间**：约 8 分钟（编译1次）

### 5.4 本地 deploy.sh 使用方式

**保持完全不变**！`deploy.sh` 无需任何修改：

```bash
# deploy.sh（无需修改）
docker-compose build  # ← 默认 BUILD_IN_DOCKER=true

# 或直接
docker build -t polyhermes:local .  # ← 也会在 Docker 内编译
```

**流程**：
```
1. docker build 开始
2. BUILD_IN_DOCKER=true（默认值）
3. Docker 内部执行 gradle bootJar
4. Docker 内部执行 npm run build
5. 构建完成
```

**时间**：约 12 分钟（首次），约 5 分钟（有缓存）

### 5.5 本地开发其他方式

#### **方式1：直接运行（推荐）**

```bash
# 后端
cd backend
gradle bootRun

# 前端（新终端）
cd frontend
npm run dev
```

#### **方式2：先编译再构建（快速）**

```bash
# 1. 编译
cd backend && gradle bootJar && cd ..
cd frontend && npm ci && npm run build && cd ..

# 2. Docker 构建（跳过编译）
docker build -t polyhermes:local \
  --build-arg BUILD_IN_DOCKER=false \
  --build-arg VERSION=local .
```

### 5.7 为什么选择混合方案？

**核心问题**：如何平衡 GitHub Actions 的性能和本地开发的便利性？

| 方案 | Actions 时间 | deploy.sh | 维护成本 | 推荐度 |
|------|-------------|-----------|---------|--------|
| **简化版** | 8分钟 | ❌ 需要改造 | 低 | ⭐⭐⭐ |
| **多阶段（2次编译）** | 13分钟 | ✅ 兼容 | 低 | ⭐⭐⭐ |
| **混合方案** | 8分钟 | ✅ 兼容 | 中 | ⭐⭐⭐⭐⭐ |

**混合方案的价值**：
1. ✅ **GitHub Actions 性能最优**
   - 只编译1次（8分钟）
   - 与简化版相同

2. ✅ **本地开发零影响**
   - `./deploy.sh` 保持不变
   - 不需要指导用户改变习惯

3. ✅ **Docker 镜像自包含**
   - 可以独立构建
   - 不依赖外部产物

4. ⚠️ **唯一代价**
   - Dockerfile 增加条件判断
   - 但这是一次性成本

**对比示例**：

```
用户A（GitHub Actions 发布）:
  → BUILD_IN_DOCKER=false
  → 8 分钟完成
  
用户B（本地部署测试）:
  → ./deploy.sh
  → BUILD_IN_DOCKER=true（自动）
  → 12 分钟完成（首次），5分钟（有缓存）
  → 无需任何额外操作
```

### 5.8 启动脚本

`docker/start.sh`：

```bash
#!/bin/bash
set -e

# 1. 启动更新服务（后台，端口 9090）
python3 /app/update-service.py &

# 2. 启动后端服务（后台，端口 8000）
java -jar /app/app.jar --spring.profiles.active=${SPRING_PROFILES_ACTIVE:-prod} &

# 3. 等待后端就绪
for i in {1..60}; do
    curl -f http://localhost:8000/api/system/health && break
    sleep 1
done

# 4. 启动 Nginx（前台运行，保持容器存活）
exec nginx -g "daemon off;"
```

### 5.3 更新服务 (update-service.py)

核心功能：
```python
@app.route('/check')       # 检查更新
@app.route('/update')      # 执行更新
@app.route('/status')      # 更新状态
@app.route('/logs')        # 更新日志
```

**详细代码见实施方案文档**。

---

## 6. Nginx 反向代理配置

### 6.1 方案说明

**采用 Nginx 直接代理方案，不需要后端 Controller**

**优势**：
- ✅ 减少调用链路（前端 → Nginx → Python，而不是 前端 → 后端 → Python）
- ✅ 减少代码量（不需要写 Controller 和 DTO）
- ✅ 更新服务真正独立（后端崩溃不影响更新功能）
- ✅ 降低维护成本

### 6.2 Nginx 配置

修改 `docker/nginx.conf`：

```nginx
http {
    # ... 现有配置保持不变
    
    server {
        listen 80;
        server_name _;
        
        # 前端静态文件
        location / {
            root /usr/share/nginx/html;
            index index.html;
            try_files $uri $uri/ /index.html;
        }
        
        # 后端 API（保持不变）
        location /api/ {
            proxy_pass http://localhost:8000;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
        }
        
        # 【新增】更新服务 API（直接代理到 Python）
        location /api/update/ {
            # 代理到更新服务
            proxy_pass http://localhost:9090/;
            
            # 传递请求头
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            
            # 传递认证头（用于权限验证）
            proxy_set_header Authorization $http_authorization;
            
            # 超时设置（更新操作可能需要较长时间）
            proxy_read_timeout 300s;
            proxy_connect_timeout 10s;
            proxy_send_timeout 300s;
        }
    }
}
```

**URL 映射**：
```
前端请求                     → Nginx 代理到                   → Python 处理
/api/update/check           → http://localhost:9090/check    → GET /check
/api/update/execute         → http://localhost:9090/update   → POST /update
/api/update/status          → http://localhost:9090/status   → GET /status
/api/update/logs            → http://localhost:9090/logs     → GET /logs
/api/update/version         → http://localhost:9090/version  → GET /version
```

### 6.3 Python 更新服务权限验证

在 `update-service.py` 中增加权限验证：

```python
# update-service.py

import requests

BACKEND_URL = 'http://localhost:8000'

def check_admin_permission(request):
    """
    检查管理员权限
    从请求头获取 Authorization token，调用后端验证
    """
    auth_header = request.headers.get('Authorization')
    if not auth_header:
        return False
    
    try:
        # 调用后端的权限验证接口
        response = requests.get(
            f'{BACKEND_URL}/api/auth/verify',
            headers={'Authorization': auth_header},
            timeout=3
        )
        return response.status_code == 200
    except Exception as e:
        logger.error(f"权限验证失败: {e}")
        return False


@app.route('/update', methods=['POST'])
def trigger_update():
    """执行更新（需要管理员权限）"""
    
    # 【新增】权限检查
    if not check_admin_permission(request):
        return jsonify({
            'code': 403,
            'data': None,
            'message': '需要管理员权限'
        }), 403
    
    if update_status['updating']:
        return jsonify({
            'code': 409,
            'data': None,
            'message': '正在更新中，请稍后'
        }), 409
    
    # 异步执行更新
    import threading
    thread = threading.Thread(target=perform_update, args=('latest',))
    thread.start()
    
    return jsonify({
        'code': 0,
        'data': '更新已启动',
        'message': 'success'
    })


@app.route('/logs', methods=['GET'])
def get_logs():
    """获取更新日志（需要管理员权限）"""
    
    # 【新增】权限检查
    if not check_admin_permission(request):
        return jsonify({
            'code': 403,
            'data': None,
            'message': '需要管理员权限'
        }), 403
    
    try:
        if LOG_FILE.exists():
            with open(LOG_FILE) as f:
                lines = f.readlines()
                return jsonify({
                    'code': 0,
                    'data': ''.join(lines[-1000:]),
                    'message': 'success'
                })
        return jsonify({
            'code': 0,
            'data': '',
            'message': 'success'
        })
    except Exception as e:
        return jsonify({
            'code': 500,
            'data': None,
            'message': str(e)
        }), 500
```

**统一的 API 响应格式**：

```python
# 成功响应
{
    "code": 0,
    "data": {...},
    "message": "success"
}

# 错误响应
{
    "code": 403,     # 或 500, 409 等
    "data": null,
    "message": "错误信息"
}
```

### 6.4 前端调用方式

前端直接调用 `/api/update/*`，Nginx 会自动代理到 Python 服务：

```typescript
// 前端 API 调用
import axios from 'axios';

// 检查更新（无需权限）
const checkUpdate = async () => {
    const response = await axios.get('/api/update/check');
    return response.data;  // { code: 0, data: {...}, message: 'success' }
};

// 执行更新（需要管理员权限，会自动从 localStorage 获取 token）
const executeUpdate = async () => {
    const response = await axios.post('/api/update/execute');
    return response.data;
};

// 获取更新状态（无需权限）
const getUpdateStatus = async () => {
    const response = await axios.get('/api/update/status');
    return response.data;
};

// 获取更新日志（需要管理员权限）
const getUpdateLogs = async () => {
    const response = await axios.get('/api/update/logs');
    return response.data;
};
```

**Axios 自动携带 Authorization**：

```typescript
// axios 拦截器（已有配置）
axios.interceptors.request.use((config) => {
    const token = localStorage.getItem('token');
    if (token) {
        config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
});
```

### 6.5 不需要创建的文件

**删除以下内容**（不需要实现）：
- ❌ `SystemUpdateController.kt` - 不需要后端 Controller
- ❌ `UpdateDto.kt` - 不需要 DTO
- ❌ `SystemUpdateService.kt` - 不需要 Service 层

**节省代码量**：约 200 行

---

## 7. 版本号识别机制

### 7.1 版本号来源

```
Docker 构建时:
  Release Tag (v1.3.0) 
    → Dockerfile ARG VERSION
    → /app/version.json {"version": "1.3.0"}

运行时检查更新:
  1. 读取: /app/version.json → "1.3.0"
  2. 请求: GitHub API /releases/latest → "v1.4.0"
  3. 比较: "1.4.0" > "1.3.0" → True
```

### 7.2 前端获取当前版本

**完整链路**：

```
前端调用 GET /api/update/version
  ↓
Nginx 代理到 http://localhost:9090/version
  ↓
Python 读取 /app/version.json
  ↓
返回 { code: 0, data: { version: "1.3.0", ... } }
```

**前端代码示例**：

```typescript
// 获取当前版本
const getCurrentVersion = async () => {
    try {
        const response = await axios.get('/api/update/version');
        
        if (response.data.code === 0) {
            const { version, tag, buildTime } = response.data.data;
            return {
                version,     // "1.3.0"
                tag,         // "v1.3.0"
                buildTime    // "2026-01-20T15:30:00Z"
            };
        }
    } catch (error) {
        console.error('获取版本失败:', error);
        return { version: 'unknown', tag: 'unknown', buildTime: '' };
    }
};
```

**API 响应格式**：

```json
{
    "code": 0,
    "data": {
        "version": "1.3.0",
        "tag": "v1.3.0",
        "buildTime": "2026-01-20T15:30:00Z"
    },
    "message": "success"
}
```

**在组件中使用**：

```typescript
// SystemUpdate.tsx
const SystemUpdate: React.FC = () => {
    const [currentVersion, setCurrentVersion] = useState('加载中...');
    
    useEffect(() => {
        // 页面加载时获取当前版本
        const fetchVersion = async () => {
            const response = await axios.get('/api/update/version');
            if (response.data.code === 0) {
                setCurrentVersion(response.data.data.version);
            }
        };
        fetchVersion();
    }, []);
    
    return (
        <div>
            <p>当前版本: {currentVersion}</p>
        </div>
    );
};
```

### 7.3 获取远程版本

```python
# 更新服务请求 GitHub API
url = 'https://api.github.com/repos/linlea666/PolyHermes-main/releases/latest'
response = requests.get(url)
data = response.json()

tag = data['tag_name']           # "v1.3.0"
version = tag.lstrip('v')        # "1.3.0"
assets = data['assets']          # 包含更新包
```

### 7.4 获取编译产物

```python
# 从 Release Assets 中查找更新包
for asset in data['assets']:
    if asset['name'].endswith('-update.tar.gz'):
        download_url = asset['browser_download_url']
        # 下载: https://github.com/.../releases/download/v1.3.0/polyhermes-v1.3.0-update.tar.gz
        break
```

### 7.5 版本号更新流程

**更新前**：
```
/app/version.json: {"version": "1.2.0"}
前端获取: "1.2.0"
```

**执行更新**：
```
1. 下载 polyhermes-v1.3.0-update.tar.gz
2. 解压得到新的 version.json: {"version": "1.3.0"}
3. 替换 /app/version.json
```

**更新后**：
```
/app/version.json: {"version": "1.3.0"}
前端获取: "1.3.0"
```

---

## 8. 使用流程

### 8.1 发布新版本

```bash
# 1. 创建并推送 tag
git tag v1.3.0
git push origin v1.3.0

# 2. 在 GitHub 创建 Release
#    - Tag: v1.3.0
#    - Title: Release v1.3.0
#    - Description: ## 新功能 ...

# 3. 自动触发 GitHub Actions
#    - 构建后端 + 前端
#    - 打包更新包
#    - 上传到 Release Assets
#    - 构建 Docker 镜像
```

### 8.2 用户更新

```
1. 登录系统
2. 系统设置 → 系统更新
3. 点击"检查更新"
4. 点击"立即升级"
5. 等待 30-60 秒
6. 页面自动刷新
```

---

## 9. 关键要点

### 9.1 文件命名规范

更新包文件名**必须**遵循：
```
polyhermes-{tag}-update.tar.gz

正确: polyhermes-v1.3.0-update.tar.gz
错误: update-v1.3.0.tar.gz
```

### 9.2 GitHub Actions 冲突避免

❌ **错误做法**：创建新的 `release-build.yml` 文件

✅ **正确做法**：修改现有的 `docker-build.yml`，在构建 Docker 镜像之前增加步骤

### 9.3 版本号格式

统一使用：`vX.Y.Z` 格式

- Tag: `v1.3.0`
- version.json: `"version": "1.3.0"`（去掉 v）
- 文件名: `polyhermes-v1.3.0-update.tar.gz`（保留 v）

---

## 10. 常见问题

### Q1: 为什么不创建新的 workflow 文件？

A: 现有的 `docker-build.yml` 已经监听 `release.published` 事件。如果创建新文件也监听同一事件，会导致两个 workflow 同时运行，造成资源浪费和潜在冲突。

### Q2: Docker 镜像和更新包的关系？

A: 
- **Docker 镜像**：包含完整应用，用于全新部署
- **更新包**：仅包含 JAR + 前端文件，用于在线更新

两者独立但同时生成，给用户不同的部署选择。

### Q3: 更新失败会怎样？

A: 更新服务会：
1. 自动备份当前版本
2. 执行更新
3. 健康检查（30秒）
4. 失败则自动回滚到备份版本

### Q4: 如何测试更新功能而不影响生产环境？

A: 使用 **Pre-release** 机制：

**创建测试版本**：
```bash
git tag v1.3.0-beta
git push origin v1.3.0-beta
# GitHub 创建 Release，勾选 "This is a pre-release"
```

**测试环境配置**：
```bash
docker run -e ALLOW_PRERELEASE=true wrbug/polyhermes:v1.3.0-beta
```

**生产环境配置**（默认）：
```bash
docker run wrbug/polyhermes:latest  # 不启用 pre-release
```

**特性**：
- ✅ Pre-release 不会触发 Telegram 通知
- ✅ Pre-release 不会推送到 `latest` 标签
- ✅ 测试环境启用 `ALLOW_PRERELEASE=true` 可检测 pre-release 版本
- ✅ 生产环境默认只检测正式版本

---

## 11. Pre-release 测试策略

### 11.1 工作流程

```
开发完成
  ↓
创建 Pre-release (v1.3.0-beta)
  ↓
GitHub Actions 构建（不发 TG）
  ↓
上传更新包到 Release Assets
  ↓
测试环境拉取并测试
  ↓
测试通过
  ↓
创建正式 Release (v1.3.0)
  ↓
GitHub Actions 构建（发 TG）
  ↓
生产环境更新
```

### 11.2 GitHub Actions 调整

**检测 Pre-release**：

在 `.github/workflows/docker-build.yml` 中增加检测：

```yaml
jobs:
  build-and-push:
    runs-on: ubuntu-latest
    
    permissions:
      contents: write
    
    steps:
      - name: Checkout code
        uses: actions/checkout@v4
        with:
          ref: ${{ github.event.release.tag_name }}
      
      - name: Extract version and check if pre-release
        id: version
        run: |
          TAG="${{ github.event.release.tag_name }}"
          VERSION=${TAG#v}
          IS_PRERELEASE="${{ github.event.release.prerelease }}"
          
          echo "TAG=$TAG" >> $GITHUB_OUTPUT
          echo "VERSION=$VERSION" >> $GITHUB_OUTPUT
          echo "IS_PRERELEASE=$IS_PRERELEASE" >> $GITHUB_OUTPUT
          
          if [ "$IS_PRERELEASE" = "true" ]; then
            echo "📋 这是 Pre-release: $TAG"
          else
            echo "📦 这是正式版本: $TAG"
          fi
      
      # ... 其他构建步骤
      
      # Docker 推送（Pre-release 不推送到 latest）
      - name: Build and push Docker image
        uses: docker/build-push-action@v5
        with:
          context: .
          push: true
          tags: |
            wrbug/polyhermes:${{ steps.version.outputs.TAG }}
            ${{ steps.version.outputs.IS_PRERELEASE == 'false' && 'wrbug/polyhermes:latest' || '' }}
          build-args: |
            BUILD_IN_DOCKER=false
            VERSION=${{ steps.version.outputs.VERSION }}
            GIT_TAG=${{ steps.version.outputs.TAG }}
      
      # Telegram 通知（仅正式版本）
      - name: Send Telegram notification
        if: steps.version.outputs.IS_PRERELEASE == 'false'
        env:
          TELEGRAM_BOT_TOKEN: ${{ secrets.TELEGRAM_BOT_TOKEN }}
          TELEGRAM_CHAT_ID: ${{ secrets.TELEGRAM_CHAT_ID }}
        run: |
          if [ -z "$TELEGRAM_BOT_TOKEN" ]; then
            echo "⚠️ Telegram 未配置，跳过通知"
            exit 0
          fi
          
          MESSAGE="✅ <b>PolyHermes ${{ steps.version.outputs.TAG }} 发布成功</b>

📦 版本: ${{ steps.version.outputs.VERSION }}
🔗 <a href=\"${{ github.event.release.html_url }}\">查看 Release</a>

<b>已上传:</b>
- Docker 镜像: wrbug/polyhermes:${{ steps.version.outputs.TAG }}
- 更新包: polyhermes-${{ steps.version.outputs.TAG }}-update.tar.gz"
          
          curl -s -X POST "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage" \
            -H "Content-Type: application/json" \
            -d "$(jq -n \
              --arg chat_id \"$TELEGRAM_CHAT_ID\" \
              --arg text \"$MESSAGE\" \
              '{chat_id: $chat_id, text: $text, parse_mode: \"HTML\"}')"
```

**关键点**：
1. `${{ github.event.release.prerelease }}` - GitHub 自动提供的判断
2. `if: steps.version.outputs.IS_PRERELEASE == 'false'` - 仅正式版本发 TG
3. Tags 推送逻辑 - Pre-release 不推送 `latest`

### 11.3 更新服务调整

在 `docker/update-service.py` 中增加环境变量支持：

```python
# 是否允许检测 pre-release 版本
ALLOW_PRERELEASE = os.getenv('ALLOW_PRERELEASE', 'false').lower() == 'true'

def fetch_latest_release():
    """获取最新 Release"""
    try:
        if ALLOW_PRERELEASE:
            # 测试模式：获取所有 Release（包括 pre-release）
            url = f'https://api.github.com/repos/{GITHUB_REPO}/releases'
            response = requests.get(url, headers={'Accept': 'application/vnd.github.v3+json'})
            releases = response.json()
            
            if releases and len(releases) > 0:
                latest = releases[0]  # 最新的（可能是 pre-release）
                logger.info(f"检测到版本: {latest['tag_name']} (pre-release: {latest.get('prerelease', False)})")
                return {
                    'tag': latest['tag_name'],
                    'name': latest['name'],
                    'body': latest['body'],
                    'published_at': latest['published_at'],
                    'assets': latest['assets'],
                    'prerelease': latest.get('prerelease', False)
                }
        else:
            # 生产模式：只获取正式版本（GitHub API 的 /latest 自动排除 pre-release）
            url = f'https://api.github.com/repos/{GITHUB_REPO}/releases/latest'
            response = requests.get(url, headers={'Accept': 'application/vnd.github.v3+json'})
            
            if response.status_code == 200:
                data = response.json()
                return {
                    'tag': data['tag_name'],
                    'name': data['name'],
                    'body': data['body'],
                    'published_at': data['published_at'],
                    'assets': data['assets'],
                    'prerelease': False
                }
        
        return None
        
    except Exception as e:
        logger.error(f"获取 Release 失败: {e}")
        return None
```

### 11.4 Docker 启动配置

**测试环境**（`docker-compose.test.yml`）：

```yaml
version: '3.8'

services:
  app:
    image: wrbug/polyhermes:v1.3.0-beta
    ports:
      - "8080:80"
    environment:
      ALLOW_PRERELEASE: "true"  # ← 启用 pre-release 检测
      GITHUB_REPO: "linlea666/PolyHermes-main"
      SPRING_PROFILES_ACTIVE: "test"
```

**生产环境**（保持不变）：

```yaml
version: '3.8'

services:
  app:
    image: wrbug/polyhermes:latest
    # ALLOW_PRERELEASE 默认为 false，只检测正式版本
```

### 11.5 测试流程

1. **创建 Pre-release**
   ```bash
   git tag v1.3.0-beta
   git push origin v1.3.0-beta
   # GitHub: Create Release → 勾选 "This is a pre-release"
   ```

2. **自动构建**
   - ✅ GitHub Actions 构建镜像
   - ✅ 上传更新包
   - ❌ 不发送 Telegram 通知（因为 IS_PRERELEASE=true）
   - ❌ 不推送到 `latest` 标签

3. **测试环境验证**
   ```bash
   # 拉取测试镜像
   docker pull wrbug/polyhermes:v1.3.0-beta
   
   # 启动测试容器
   docker-compose -f docker-compose.test.yml up -d
   
   # 系统内检查更新（会检测到 v1.3.0-beta）
   # 点击"立即升级"测试更新流程
   ```

4. **测试通过后发布正式版**
   ```bash
   git tag v1.3.0
   git push origin v1.3.0
   # GitHub: Create Release（不勾选 pre-release）
   ```

5. **正式版本发布**
   - ✅ GitHub Actions 构建镜像
   - ✅ 上传更新包
   - ✅ 发送 Telegram 通知
   - ✅ 推送到 `latest` 标签

---

**方案版本**: v1.0  
**最后更新**: 2026-01-20
