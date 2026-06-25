#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="$SCRIPT_DIR/deploy.env"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Файл deploy/deploy.env не найден."
  echo "Скопируйте пример: cp deploy/deploy.env.example deploy/deploy.env"
  exit 1
fi

# shellcheck source=/dev/null
source "$ENV_FILE"

: "${DEPLOY_HOST:?DEPLOY_HOST не задан в deploy/deploy.env}"
: "${DEPLOY_USER:?DEPLOY_USER не задан в deploy/deploy.env}"
: "${DEPLOY_PATH:?DEPLOY_PATH не задан в deploy/deploy.env}"

PORT="${PORT:-3000}"
MCP_ALLOWED_HOSTS="${MCP_ALLOWED_HOSTS:-$DEPLOY_HOST}"
REMOTE="${DEPLOY_USER}@${DEPLOY_HOST}"

echo "==> Синхронизация кода на ${REMOTE}:${DEPLOY_PATH}"
rsync -avz --delete \
  --exclude '.git' \
  --exclude 'build' \
  --exclude '.gradle' \
  --exclude '.idea' \
  --exclude 'deploy/deploy.env' \
  "$PROJECT_ROOT/" "${REMOTE}:${DEPLOY_PATH}/"

echo "==> Сборка и запуск контейнера"
ssh "$REMOTE" "cd ${DEPLOY_PATH} && PORT=${PORT} MCP_ALLOWED_HOSTS=${MCP_ALLOWED_HOSTS} docker compose up -d --build"

echo ""
echo "Готово. MCP сервер доступен по адресу:"
echo "  http://${DEPLOY_HOST}:${PORT}/mcp"
echo ""
echo "Логи на сервере:"
echo "  ./logs.sh          # последние 100 строк"
echo "  ./logs.sh -f       # в реальном времени"
