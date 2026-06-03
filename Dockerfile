# 多阶段构建：前后端一体化部署
#
# 默认 target 是 runtime-external：
# - deploy.sh/CI 先编译 backend/build/libs/*.jar 和 frontend/dist
# - Dockerfile 只 COPY 这些产物，不再执行 npm/Gradle 编译
#
# 如需 Docker 内完整编译：
#   docker build --target runtime-docker --build-arg BUILD_IN_DOCKER=true .
ARG BUILD_IN_DOCKER=false

# ==================== Docker 内编译：前端 ====================
FROM node:18-alpine AS frontend-build

WORKDIR /app/frontend

ARG VERSION=dev
ARG GIT_TAG=
ARG GITHUB_REPO_URL=https://github.com/linlea666/PolyHermes-main

ENV VERSION=${VERSION}
ENV GIT_TAG=${GIT_TAG}
ENV GITHUB_REPO_URL=${GITHUB_REPO_URL}

COPY frontend/package*.json ./
RUN npm ci --prefer-offline --no-audit --no-fund

COPY frontend/ ./
RUN npm run build && \
    mkdir -p /artifacts && \
    cp -r dist /artifacts/dist

# ==================== 外部产物：前端 ====================
FROM alpine:3.20 AS frontend-external

WORKDIR /artifacts
COPY frontend/dist ./dist
RUN test -d dist && test -n "$(ls -A dist)"

# ==================== Docker 内编译：后端 ====================
FROM gradle:8.5-jdk17 AS backend-build

WORKDIR /app/backend

COPY backend/build.gradle.kts backend/settings.gradle.kts ./
COPY backend/gradle ./gradle
COPY backend/gradlew ./gradlew
RUN chmod +x ./gradlew && \
    ./gradlew dependencies --no-daemon -Dorg.gradle.workers.max=1 || true

COPY backend/src ./src
RUN ./gradlew bootJar --no-daemon -Dorg.gradle.workers.max=1

RUN set -e; \
    mkdir -p /artifacts; \
    JAR="$(find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' | head -n 1)"; \
    if [ -z "$JAR" ]; then \
      echo "错误：找不到可执行 JAR（已排除 *-plain.jar）"; \
      exit 1; \
    fi; \
    cp "$JAR" /artifacts/app.jar

# ==================== 外部产物：后端 ====================
FROM alpine:3.20 AS backend-external

WORKDIR /artifacts
COPY backend/build/libs/*.jar ./libs/
RUN set -e; \
    JAR="$(find libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' | head -n 1)"; \
    if [ -z "$JAR" ]; then \
      echo "错误：找不到外部构建的可执行 JAR（backend/build/libs/*.jar，已排除 *-plain.jar）"; \
      exit 1; \
    fi; \
    cp "$JAR" app.jar

# ==================== 运行环境基础层 ====================
FROM eclipse-temurin:17-jre-jammy AS runtime-base

WORKDIR /app

RUN apt-get update && \
    apt-get install -y nginx curl tzdata jq python3 python3-flask python3-requests && \
    rm -rf /var/lib/apt/lists/* && \
    rm -rf /etc/nginx/sites-enabled/default

COPY docker/nginx.conf /etc/nginx/nginx.conf

RUN mkdir -p /app/updates /app/backups /var/log/polyhermes
COPY docker/update-service.py /app/update-service.py
COPY docker/start.sh /app/start.sh
RUN chmod +x /app/start.sh

ARG VERSION=dev
ARG GIT_TAG=dev
RUN echo "{\"version\":\"${VERSION}\",\"tag\":\"${GIT_TAG}\",\"buildTime\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}" > /app/version.json

RUN useradd -m -u 1000 appuser
RUN mkdir -p /var/log/nginx /var/lib/nginx /var/cache/nginx /var/run && \
    chown -R appuser:appuser /app && \
    chown -R root:root /usr/share/nginx/html /var/log/nginx /var/lib/nginx /var/cache/nginx /etc/nginx /var/run

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -f http://localhost/api/system/health || exit 1

ENTRYPOINT ["/app/start.sh"]

# ==================== 最终镜像：Docker 内编译 ====================
FROM runtime-base AS runtime-docker

COPY --from=frontend-build /artifacts/dist /usr/share/nginx/html
COPY --from=backend-build /artifacts/app.jar app.jar

# ==================== 最终镜像：外部产物（默认） ====================
FROM runtime-base AS runtime-external

COPY --from=frontend-external /artifacts/dist /usr/share/nginx/html
COPY --from=backend-external /artifacts/app.jar app.jar
