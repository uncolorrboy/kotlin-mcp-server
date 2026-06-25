#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
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

REMOTE="${DEPLOY_USER}@${DEPLOY_HOST}"
LOG_FILE="${LOG_FILE_PATH:-/data/app.log}"

FOLLOW=false
TAIL_LINES=100
SERVICE="mcp-server"

while [[ $# -gt 0 ]]; do
  case "$1" in
    -f|--follow)
      FOLLOW=true
      shift
      ;;
    --tail)
      TAIL_LINES="${2:?Укажите число строк после --tail}"
      shift 2
      ;;
    -h|--help)
      cat <<EOF
Просмотр логов MCP-сервера на удалённом сервере.
Файл логов в контейнере: ${LOG_FILE} (хранится до \${LOG_MAX_LINES:-3000} строк)

Использование:
  ./deploy/logs.sh              последние 100 строк
  ./deploy/logs.sh -f           следить в реальном времени
  ./deploy/logs.sh --tail 500   последние 500 строк
  ./deploy/logs.sh -f --tail 50 следить, начиная с 50 последних строк

Локально (без SSH):
  docker compose exec -T mcp-server tail -f ${LOG_FILE}
EOF
      exit 0
      ;;
    *)
      echo "Неизвестный аргумент: $1 (используйте -h для справки)"
      exit 1
      ;;
  esac
done

if [[ "$FOLLOW" == true ]]; then
  CMD="cd ${DEPLOY_PATH} && docker compose exec -T ${SERVICE} tail -n ${TAIL_LINES} -f ${LOG_FILE}"
else
  CMD="cd ${DEPLOY_PATH} && docker compose exec -T ${SERVICE} tail -n ${TAIL_LINES} ${LOG_FILE}"
fi

echo "==> Логи ${REMOTE}:${DEPLOY_PATH} (${LOG_FILE})"
ssh -t "$REMOTE" "$CMD"
