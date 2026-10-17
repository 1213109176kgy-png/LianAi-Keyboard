#!/usr/bin/env bash
# 每次改动后台代码后执行本脚本，即可更新到服务器：
#   bash scripts/deploy.sh
# 前置：本机已配置 SSH 免密登录 root@121.41.53.253（密钥已部署）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOST="root@121.41.53.253"
REMOTE_DIR="/www/wwwroot/lianai-admin"
NODE_BIN="/www/server/nodejs/v20.18.1/bin"

echo "[1/4] 构建前端..."
npm --prefix "$ROOT/web" run build

echo "[2/4] 同步服务端代码与前端产物（跳过线上 data/ 数据库）..."
rsync -avz --exclude '._*' --exclude '.DS_Store' \
  "$ROOT"/server/*.mjs "$ROOT/server/package.json" "$ROOT/server/package-lock.json" \
  "$HOST:$REMOTE_DIR/"
rsync -avz --delete --exclude '._*' --exclude '.DS_Store' \
  "$ROOT/web/dist/" "$HOST:$REMOTE_DIR/dist/"

echo "[3/4] 安装依赖并重启 PM2..."
ssh "$HOST" "export PATH=$NODE_BIN:\$PATH && cd $REMOTE_DIR && \
  find . -maxdepth 2 -name '._*' -delete 2>/dev/null; \
  npm ci --omit=dev --no-audit --no-fund --registry=https://registry.npmjs.org && \
  pm2 restart lianai-admin"

echo "[4/4] 验证线上接口..."
status=000
for attempt in {1..15}; do
  status=$(curl -s -o /dev/null -w '%{http_code}' https://lianai.bahuang.tech/api/pay/config || true)
  if [ "$status" = "401" ]; then break; fi
  sleep 1
done
if [ "$status" = "401" ]; then
  echo "✅ 部署成功：/api/pay/config 已上线（未登录返回 401 符合预期）"
else
  echo "⚠️ /api/pay/config 返回 ${status}（预期 401），请检查 pm2 logs lianai-admin"
  exit 1
fi
curl -fsS https://lianai.bahuang.tech/api/app/payment/channels
curl -fsS -o /dev/null https://lianai.bahuang.tech/h5/alipay-return.html
curl -s -o /dev/null -w 'App 升级接口 /api/app/latest: HTTP %{http_code}\n' https://lianai.bahuang.tech/api/app/latest
