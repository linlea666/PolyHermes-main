# PolyHermes 简化源码部署

适合当前仓库的日常使用方式：本地改代码并推送到 GitHub，服务器拉取最新代码后用 Docker 重新构建运行。

## 首次部署

```bash
git clone https://github.com/linlea666/PolyHermes-main.git
cd PolyHermes-main
chmod +x deploy.sh
./deploy.sh
```

第一次运行时，如果脚本创建了 `.env` 并退出，检查 `.env` 后再运行一次：

```bash
./deploy.sh
```

## 后续更新

```bash
cd PolyHermes-main
git pull
./deploy.sh
```

也可以直接执行：

```bash
docker compose up -d --build
```

## 常用检查

```bash
docker compose ps
docker compose logs -f
docker compose down
```

如果服务器只有老版本 Docker Compose，也可以把 `docker compose` 换成 `docker-compose`。

## 配置提醒

- `.env` 只放在服务器，不要提交到 GitHub。
- `DB_PASSWORD`、`JWT_SECRET`、`ADMIN_RESET_PASSWORD_KEY` 必须使用安全随机值。
- 宝塔面板的 Docker 页面可用于查看容器状态、日志和端口映射；部署和更新建议仍在终端里执行上面的命令。
