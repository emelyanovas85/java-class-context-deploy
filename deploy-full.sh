#!/usr/bin/env bash
# =============================================================================
# deploy-full.sh — разворачивание полного стека (java-class-context + mcp-wrapper)
# на удалённой машине через docker-compose.full.yml
#
# Собирает оба Docker-образа локально, передаёт их на сервер и поднимает
# оба контейнера одной командой. Порты фиксированы:
#   8084 — java-class-context REST API (основной сервис)
#   8086 — java-class-context-mcp MCP-обёртка (подключается к Open WebUI)
#
# Использование:
#   ./deploy-full.sh [ОПЦИИ]
#
# Опции:
#   -h, --host       SSH-хост удалённой машины (по умолчанию: 10.1.5.97)
#   -u, --user       SSH-пользователь (по умолчанию: svc-local-adm)
#   -p, --port       SSH-порт (по умолчанию: 22)
#   -i, --identity   Путь к приватному SSH-ключу (необязательно)
#   -b, --branch     Ветка Git для деплоя (по умолчанию: main)
#   --no-build       Не пересобирать образы (использовать существующие на сервере)
#   --help           Показать справку
#
# Примеры:
#   ./deploy-full.sh
#   ./deploy-full.sh -h 192.168.1.100 -u deploy
#   ./deploy-full.sh --no-build
#   ./deploy-full.sh -i ~/.ssh/id_rsa -b feature/my-branch
#
# Примечание: удалённый хост не имеет доступа в интернет.
# Оба Docker-образа собираются локально и передаются через SSH
# (docker save | ssh docker load) без промежуточных файлов.
# Файл docker-compose.full.yml передаётся через scp с локальной машины.
# =============================================================================

set -euo pipefail

# ── Цвета ────────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; NC='\033[0m'

log()   { echo -e "${BLUE}[$(date '+%H:%M:%S')]${NC} $*"; }
ok()    { echo -e "${GREEN}[$(date '+%H:%M:%S')] \u2713${NC} $*"; }
warn()  { echo -e "${YELLOW}[$(date '+%H:%M:%S')] \u26a0${NC} $*"; }
error() { echo -e "${RED}[$(date '+%H:%M:%S')] \u2717${NC} $*" >&2; exit 1; }

# ── Корень репозитория ────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ── Значения по умолчанию ─────────────────────────────────────────────────────
REMOTE_HOST="10.1.5.97"
REMOTE_USER="svc-local-adm"
REMOTE_PORT="22"
SSH_KEY=""
GIT_BRANCH="main"
NO_BUILD=false

APP_DIR="java-class-context-full"
IMAGE_MAIN="java-class-context:latest"
IMAGE_MCP="java-class-context-mcp:latest"
PORT_MAIN="8084"
PORT_MCP="8086"

# ── Разбор аргументов ─────────────────────────────────────────────────────────
usage() {
  grep '^#' "$0" | grep -v '#!/' | sed 's/^# \{0,2\}//'
  exit 0
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--host)     REMOTE_HOST="$2"; shift 2 ;;
    -u|--user)     REMOTE_USER="$2"; shift 2 ;;
    -p|--port)     REMOTE_PORT="$2"; shift 2 ;;
    -i|--identity) SSH_KEY="$2"; shift 2 ;;
    -b|--branch)   GIT_BRANCH="$2"; shift 2 ;;
    --no-build)    NO_BUILD=true; shift ;;
    --help)        usage ;;
    *) error "Неизвестный аргумент: $1. Используйте --help для справки." ;;
  esac
done

# ── SSH ControlMaster: одно подключение — один ввод пароля ────────────────────
SSH_CTRL_DIR="$(mktemp -d /tmp/ssh-ctrl-XXXXXX)"
SSH_CTRL_SOCK="${SSH_CTRL_DIR}/master"

cleanup() {
  ssh -o ControlPath="${SSH_CTRL_SOCK}" -O exit "${REMOTE_HOST}" 2>/dev/null || true
  rm -rf "${SSH_CTRL_DIR}"
}
trap cleanup EXIT

SSH_BASE_OPTS="-o StrictHostKeyChecking=no -o ConnectTimeout=10 -o ControlMaster=auto -o ControlPath=${SSH_CTRL_SOCK} -o ControlPersist=300"
[[ -n "$SSH_KEY" ]] && SSH_BASE_OPTS="${SSH_BASE_OPTS} -i ${SSH_KEY}"

SSH_CMD="ssh ${SSH_BASE_OPTS} -p ${REMOTE_PORT} ${REMOTE_USER}@${REMOTE_HOST}"
SCP_CMD="scp ${SSH_BASE_OPTS} -P ${REMOTE_PORT}"

# ── Первое подключение (ввод пароля) ──────────────────────────────────────────
log "Подключение к ${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_PORT} (единственный ввод пароля)..."
$SSH_CMD "echo ok" > /dev/null 2>&1 || error "Не удалось подключиться к ${REMOTE_HOST}"
ok "Соединение установлено (дальнейшие шаги — без пароля)"

# ── Проверка зависимостей ─────────────────────────────────────────────────────
log "Проверка зависимостей на удалённой машине..."
$SSH_CMD bash << 'REMOTE_CHECK'
set -e
docker info > /dev/null 2>&1 || { echo "ERROR: docker daemon не запущен"; exit 1; }
if docker compose version &>/dev/null 2>&1; then
  echo "INFO: docker compose v2"
elif command -v docker-compose &>/dev/null; then
  echo "INFO: docker-compose v1"
  echo "WARN: docker-compose v1 достиг EOL. Рекомендуем обновиться до docker compose v2"
else
  echo "ERROR: не найден ни 'docker compose', ни 'docker-compose'"
  exit 1
fi
echo "ALL_OK"
REMOTE_CHECK
ok "Зависимости в порядке"

DOCKER_COMPOSE=$($SSH_CMD 'if docker compose version >/dev/null 2>&1; then echo "docker compose"; else echo "docker-compose"; fi')
log "Используем: ${DOCKER_COMPOSE}"

# ── Сборка образов ЛОКАЛЬНО и передача на сервер ──────────────────────────────
if [[ "${NO_BUILD}" == "false" ]]; then

  log "Сборка образа ${IMAGE_MAIN} (основной сервис)..."
  docker build -t "${IMAGE_MAIN}" -f "${SCRIPT_DIR}/Dockerfile" "${SCRIPT_DIR}" \
    || error "Ошибка сборки ${IMAGE_MAIN}"
  ok "Образ ${IMAGE_MAIN} собран"

  log "Передача ${IMAGE_MAIN} на ${REMOTE_HOST}..."
  docker save "${IMAGE_MAIN}" \
    | ssh ${SSH_BASE_OPTS} -p "${REMOTE_PORT}" "${REMOTE_USER}@${REMOTE_HOST}" 'docker load'
  ok "${IMAGE_MAIN} загружен на ${REMOTE_HOST}"

  log "Сборка образа ${IMAGE_MCP} (mcp-wrapper)..."
  docker build -t "${IMAGE_MCP}" \
    -f "${SCRIPT_DIR}/mcp-wrapper/Dockerfile" \
    "${SCRIPT_DIR}/mcp-wrapper" \
    || error "Ошибка сборки ${IMAGE_MCP}"
  ok "Образ ${IMAGE_MCP} собран"

  log "Передача ${IMAGE_MCP} на ${REMOTE_HOST}..."
  docker save "${IMAGE_MCP}" \
    | ssh ${SSH_BASE_OPTS} -p "${REMOTE_PORT}" "${REMOTE_USER}@${REMOTE_HOST}" 'docker load'
  ok "${IMAGE_MCP} загружен на ${REMOTE_HOST}"

else
  warn "Пропуск сборки (--no-build). Используются существующие образы на сервере."
fi

# ── Передача docker-compose.full.yml на сервер ────────────────────────────────
log "Передача docker-compose.full.yml на ${REMOTE_HOST}..."
$SSH_CMD "mkdir -p ${APP_DIR}"
$SCP_CMD "${SCRIPT_DIR}/docker-compose.full.yml" \
  "${REMOTE_USER}@${REMOTE_HOST}:${APP_DIR}/docker-compose.full.yml"
ok "docker-compose.full.yml передан"

# ── Деплой на сервере ─────────────────────────────────────────────────────────
log "Запуск полного стека на ${REMOTE_HOST}..."

$SSH_CMD bash -s << REMOTE_DEPLOY
set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; NC='\033[0m'

log()   { echo -e "\${BLUE}[remote \$(date '+%H:%M:%S')]\${NC} \$*"; }
ok()    { echo -e "\${GREEN}[remote \$(date '+%H:%M:%S')] \u2713\${NC} \$*"; }
warn()  { echo -e "\${YELLOW}[remote \$(date '+%H:%M:%S')] \u26a0\${NC} \$*"; }
fail()  { echo -e "\${RED}[remote \$(date '+%H:%M:%S')] \u2717\${NC} \$*" >&2; exit 1; }

APP_DIR="${APP_DIR}"
DOCKER_COMPOSE="${DOCKER_COMPOSE}"
PORT_MAIN="${PORT_MAIN}"
PORT_MCP="${PORT_MCP}"

[[ "\${APP_DIR}" != /* ]] && APP_DIR="\${HOME}/\${APP_DIR}"

cd "\${APP_DIR}"

# 1. Остановка предыдущего стека (если был)
if eval "\${DOCKER_COMPOSE} -f docker-compose.full.yml ps -q 2>/dev/null" | grep -q .; then
  log "Остановка предыдущего стека..."
  eval "\${DOCKER_COMPOSE} -f docker-compose.full.yml down --remove-orphans"
  ok "Стек остановлен"
else
  log "Запущенных контейнеров полного стека не найдено — первый запуск"
fi

# 2. Запуск
log "Запуск стека через docker-compose.full.yml..."
eval "\${DOCKER_COMPOSE} -f docker-compose.full.yml up -d --no-build"
ok "Стек запущен"

# 3. Ожидание готовности основного сервиса
log "Ожидание готовности java-context-service (порт \${PORT_MAIN}, max 90 сек)..."
MAX_WAIT=90; ELAPSED=0; HEALTHY=false
while [[ \$ELAPSED -lt \$MAX_WAIT ]]; do
  if curl -sf "http://localhost:\${PORT_MAIN}/docs" > /dev/null 2>&1; then
    HEALTHY=true; break
  fi
  printf "."; sleep 3; ELAPSED=\$((ELAPSED + 3))
done
echo ""
[[ "\$HEALTHY" != "true" ]] && { warn "java-context-service не ответил за \${MAX_WAIT} сек. Логи:"; eval "\${DOCKER_COMPOSE} -f docker-compose.full.yml logs --tail=30 java-context-service"; fail "Деплой прерван"; }
ok "java-context-service готов (\${ELAPSED} сек)"

# 4. Ожидание готовности mcp-wrapper
log "Ожидание готовности java-context-mcp (порт \${PORT_MCP}, max 90 сек)..."
MAX_WAIT=90; ELAPSED=0; HEALTHY=false
while [[ \$ELAPSED -lt \$MAX_WAIT ]]; do
  if curl -sf "http://localhost:\${PORT_MCP}/info" > /dev/null 2>&1; then
    HEALTHY=true; break
  fi
  printf "."; sleep 3; ELAPSED=\$((ELAPSED + 3))
done
echo ""
[[ "\$HEALTHY" != "true" ]] && { warn "java-context-mcp не ответил за \${MAX_WAIT} сек. Логи:"; eval "\${DOCKER_COMPOSE} -f docker-compose.full.yml logs --tail=30 java-context-mcp"; fail "Деплой прерван"; }
ok "java-context-mcp готов (\${ELAPSED} сек)"

# 5. Итог
echo ""
eval "\${DOCKER_COMPOSE} -f docker-compose.full.yml ps"
echo ""
ok "Полный стек развёрнут успешно"
SERVER_IP=\$(hostname -I | awk '{print \$1}')
echo -e "\${GREEN} Java Context API:  http://\${SERVER_IP}:\${PORT_MAIN}/docs\${NC}"
echo -e "\${GREEN} MCP SSE:           http://\${SERVER_IP}:\${PORT_MCP}/sse\${NC}"
echo -e "\${GREEN} MCP message:       http://\${SERVER_IP}:\${PORT_MCP}/mcp/message\${NC}"
echo -e "\${GREEN} MCP Info:          http://\${SERVER_IP}:\${PORT_MCP}/info\${NC}"

REMOTE_DEPLOY

ok "Деплой полного стека на ${REMOTE_HOST} завершён"
