#!/usr/bin/env bash
# =============================================================================
# deploy-full.sh — полный деплой: java-class-context + mcp-wrapper
#
# Запускает последовательно два существующих скрипта:
#   1. deploy-java-class-context.sh  — основной сервис (порт 8084)
#   2. mcp-wrapper/deploy-mcp-wrapper.sh  — MCP-обёртка (порт 8086)
#
# Использование:
#   ./deploy-full.sh [ОПЦИИ]
#
# Опции:
#   -h, --host       SSH-хост удалённой машины (по умолчанию: 10.1.5.97)
#   -u, --user       SSH-пользователь (по умолчанию: svc-local-adm)
#   -p, --port       SSH-порт (по умолчанию: 22)
#   -i, --identity   Путь к приватному SSH-ключу (необязательно)
#   -b, --branch     Ветка Git (по умолчанию: main)
#   --no-build       Не пересобирать образы
#   --help           Показать справку
#
# Примеры:
#   ./deploy-full.sh
#   ./deploy-full.sh -h 192.168.1.100 -u deploy
#   ./deploy-full.sh --no-build
# =============================================================================

set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; NC='\033[0m'

log()   { echo -e "${BLUE}[$(date '+%H:%M:%S')]${NC} $*"; }
ok()    { echo -e "${GREEN}[$(date '+%H:%M:%S')] \u2713${NC} $*"; }
error() { echo -e "${RED}[$(date '+%H:%M:%S')] \u2717${NC} $*" >&2; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ── Значения по умолчанию
REMOTE_HOST="10.1.5.97"
REMOTE_USER="svc-local-adm"
REMOTE_PORT="22"
SSH_KEY=""
GIT_BRANCH="main"
NO_BUILD=false

usage() {
  grep '^#' "$0" | grep -v '#!/' | sed 's/^# \{0,2\}//'
  exit 0
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--host)     REMOTE_HOST="$2"; shift 2 ;;
    -u|--user)     REMOTE_USER="$2"; shift 2 ;;
    -p|--port)     REMOTE_PORT="$2"; shift 2 ;;
    -i|--identity) SSH_KEY="$2";    shift 2 ;;
    -b|--branch)   GIT_BRANCH="$2"; shift 2 ;;
    --no-build)    NO_BUILD=true;   shift ;;
    --help)        usage ;;
    *) error "Неизвестный аргумент: $1. Используйте --help."; ;;
  esac
done

# ── Сборка общих аргументов для передачи в подскрипты
FORWARD_ARGS=("-h" "${REMOTE_HOST}" "-u" "${REMOTE_USER}" "-p" "${REMOTE_PORT}" "-b" "${GIT_BRANCH}")
[[ -n "${SSH_KEY}" ]] && FORWARD_ARGS+=("-i" "${SSH_KEY}")
[[ "${NO_BUILD}" == "true" ]] && FORWARD_ARGS+=("--no-build")

DEPLOY_MAIN="${SCRIPT_DIR}/deploy-java-class-context.sh"
DEPLOY_MCP="${SCRIPT_DIR}/mcp-wrapper/deploy-mcp-wrapper.sh"

[[ -x "${DEPLOY_MAIN}" ]] || chmod +x "${DEPLOY_MAIN}"
[[ -x "${DEPLOY_MCP}" ]]  || chmod +x "${DEPLOY_MCP}"

# ── Шаг 1: основной сервис
echo ""
log "=== Шаг 1/2: деплой java-class-context (8084) ==="
"${DEPLOY_MAIN}" "${FORWARD_ARGS[@]}" || error "Деплой java-class-context завершился с ошибкой"

# ── Шаг 2: MCP-обёртка
echo ""
log "=== Шаг 2/2: деплой java-class-context-mcp (8086) ==="
"${DEPLOY_MCP}" "${FORWARD_ARGS[@]}" || error "Деплой mcp-wrapper завершился с ошибкой"

echo ""
ok "Полный стек развёрнут успешно — оба сервиса работают"
echo -e "${GREEN} Java Context API: http://${REMOTE_HOST}:8084/docs${NC}"
echo -e "${GREEN} MCP SSE:          http://${REMOTE_HOST}:8086/sse${NC}"
