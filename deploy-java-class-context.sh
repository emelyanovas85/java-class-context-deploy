#!/usr/bin/env bash
# =============================================================================
# deploy-java-class-context.sh — разворачивание java-class-context на удалённой машине
#
# Использование:
#   ./deploy-java-class-context.sh [ОПЦИИ]
#
# Опции:
#   -h, --host       SSH-хост удалённой машины (по умолчанию: 10.1.5.97)
#   -u, --user       SSH-пользователь (по умолчанию: svc-local-adm)
#   -p, --port       SSH-порт (по умолчанию: 22)
#   -i, --identity   Путь к приватному SSH-ключу (необязательно)
#   -b, --branch     Ветка Git для деплоя (по умолчанию: main)
#   --no-build       Не пересобирать образ (использовать существующий)
#   --help           Показать справку
#
# Примеры:
#   ./deploy-java-class-context.sh
#   ./deploy-java-class-context.sh -h 192.168.1.100 -u deploy
#   ./deploy-java-class-context.sh -i ~/.ssh/id_rsa -b feature/my-branch
#
# Примечание: удалённый хост не имеет доступа в интернет.
# Docker-образ собирается локально, затем передаётся через SSH
# (docker save | ssh docker load) без промежуточных файлов.
# Исходники также передаются через scp с локальной машины.
# =============================================================================

set -euo pipefail

# ── Цвета ────────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; NC='\033[0m'

log()   { echo -e "${BLUE}[$(date '+%H:%M:%S')]${NC} $*"; }
ok()    { echo -e "${GREEN}[$(date '+%H:%M:%S')] \u2713${NC} $*"; }
warn()  { echo -e "${YELLOW}[$(date '+%H:%M:%S')] \u26a0${NC} $*"; }
error() { echo -e "${RED}[$(date '+%H:%M:%S')] \u2717${NC} $*" >&2; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ── Значения по умолчанию ─────────────────────────────────────────────────────
REMOTE_HOST="10.1.5.97"
REMOTE_USER="svc-local-adm"
REMOTE_PORT="22"
SSH_KEY=""
GIT_BRANCH="main"
NO_BUILD=false
APP_DIR="java-class-context"
IMAGE_NAME="java-class-context:latest"
APP_PORT="8084"

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
SCP_CMD="scp -r ${SSH_BASE_OPTS} -P ${REMOTE_PORT}"

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
  DC_VERSION=$(docker compose version --short 2>/dev/null || docker compose version | grep -oE '[0-9]+\.[0-9]+' | head -1)
  echo "INFO: docker compose v2 (${DC_VERSION})"
elif command -v docker-compose &>/dev/null; then
  DC_VERSION=$(docker-compose version --short 2>/dev/null || docker-compose version | grep -oE '[0-9]+\.[0-9]+' | head -1)
  echo "INFO: docker-compose v1 (${DC_VERSION})"
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

# ── Сборка ЛОКАЛЬНО и передача на сервер ───────────────────────────────────
if [[ "${NO_BUILD}" == "false" ]]; then
  log "Сборка Docker-образа ${IMAGE_NAME} локально..."
  docker build -t "${IMAGE_NAME}" -f "${SCRIPT_DIR}/Dockerfile" "${SCRIPT_DIR}" \
    || error "Ошибка сборки образа"
  ok "Образ ${IMAGE_NAME} собран"

  log "Передача образа на ${REMOTE_HOST} (docker save | ssh docker load)..."
  docker save "${IMAGE_NAME}" \
    | ssh ${SSH_BASE_OPTS} -p "${REMOTE_PORT}" "${REMOTE_USER}@${REMOTE_HOST}" \
      'docker load'
  ok "Образ загружен на ${REMOTE_HOST}"
else
  warn "Пропуск сборки (--no-build). Используется существующий образ на сервере."
fi

# ── Передача исходников ───────────────────────────────────────────────────────
log "Подготовка исходников (ветка: ${GIT_BRANCH})..."
LOCAL_ARCHIVE="$(mktemp /tmp/java-class-context-XXXXXX.tar.gz)"
git -C "${SCRIPT_DIR}" archive --format=tar.gz "${GIT_BRANCH}" -o "${LOCAL_ARCHIVE}" \
  || error "Не удалось создать архив."
ok "Архив создан ($(du -sh "${LOCAL_ARCHIVE}" | cut -f1))"

log "Передача архива на ${REMOTE_HOST}..."
$SSH_CMD "rm -rf ${APP_DIR} && mkdir -p ${APP_DIR}"
$SCP_CMD "${LOCAL_ARCHIVE}" "${REMOTE_USER}@${REMOTE_HOST}:${APP_DIR}/app.tar.gz"
rm -f "${LOCAL_ARCHIVE}"
ok "Архив передан"

# ── Деплой на сервере ─────────────────────────────────────────────────────────
log "Начало деплоя ветки '${GIT_BRANCH}' на ${REMOTE_HOST}:${APP_DIR}"

$SSH_CMD bash -s << REMOTE_DEPLOY
set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; NC='\033[0m'

log()   { echo -e "\${BLUE}[remote \$(date '+%H:%M:%S')]\${NC} \$*"; }
ok()    { echo -e "\${GREEN}[remote \$(date '+%H:%M:%S')] \u2713\${NC} \$*"; }
warn()  { echo -e "\${YELLOW}[remote \$(date '+%H:%M:%S')] \u26a0\${NC} \$*"; }
fail()  { echo -e "\${RED}[remote \$(date '+%H:%M:%S')] \u2717\${NC} \$*" >&2; exit 1; }

APP_DIR="${APP_DIR}"
APP_PORT="${APP_PORT}"
IMAGE_NAME="${IMAGE_NAME}"
DOCKER_COMPOSE="${DOCKER_COMPOSE}"

[[ "\${APP_DIR}" != /* ]] && APP_DIR="\${HOME}/\${APP_DIR}"

# 1. Распаковка архива
log "Распаковка архива..."
tar -xzf "\${APP_DIR}/app.tar.gz" -C "\${APP_DIR}"
rm -f "\${APP_DIR}/app.tar.gz"
ok "Исходники распакованы в \${APP_DIR}"

cd "\${APP_DIR}"

# 2. Подмена порта в docker-compose.yml
log "Настройка порта \${APP_PORT} в docker-compose.yml..."
sed -i "s|\"8080:8080\"|\"${APP_PORT}:8080\"|g" docker-compose.yml
ok "Порт настроен: \${APP_PORT}->8080"

# 3. Остановка контейнеров, запущенных из нашего образа (\${IMAGE_NAME})
#    Не трогаем чужие контейнеры: фильтруем строго по имени образа
OWN_CONTAINERS=\$(docker ps -q --filter "ancestor=\${IMAGE_NAME}" 2>/dev/null || true)
if [[ -n "\${OWN_CONTAINERS}" ]]; then
  warn "Найдены работающие контейнеры образа \${IMAGE_NAME}:"
  docker ps --filter "ancestor=\${IMAGE_NAME}" --format "  {{.ID}}  {{.Names}}  {{.Ports}}"
  log "Остановка и удаление..."
  docker stop \${OWN_CONTAINERS}
  docker rm \${OWN_CONTAINERS}
  ok "Контейнеры остановлены"
else
  log "Запущенных контейнеров образа \${IMAGE_NAME} не найдено"
fi

# 4. Запуск
log "Запуск контейнера..."
eval "\${DOCKER_COMPOSE} up -d --no-build"
ok "Контейнер запущен"

# 5. Ожидание готовности
log "Ожидание готовности приложения (max 90 сек)..."
MAX_WAIT=90; ELAPSED=0; HEALTHY=false
while [[ \$ELAPSED -lt \$MAX_WAIT ]]; do
  if curl -sf "http://localhost:\${APP_PORT}/docs" > /dev/null 2>&1; then
    HEALTHY=true; break
  fi
  printf "."; sleep 3; ELAPSED=\$((ELAPSED + 3))
done
echo ""
if [[ "\$HEALTHY" != "true" ]]; then
  warn "Приложение не ответило за \${MAX_WAIT} сек. Последние логи:"
  eval "\${DOCKER_COMPOSE} logs --tail=50"
  exit 1
fi
ok "Приложение готово (\${ELAPSED} сек)"

echo ""
eval "\${DOCKER_COMPOSE} ps"
echo ""
ok "Деплой завершён успешно"
SERVER_IP=\$(hostname -I | awk '{print \$1}')
echo -e "\${GREEN} Swagger UI:   http://\${SERVER_IP}:\${APP_PORT}/docs\${NC}"
echo -e "\${GREEN} API docs:     http://\${SERVER_IP}:\${APP_PORT}/v3/api-docs\${NC}"

REMOTE_DEPLOY

ok "Деплой на ${REMOTE_HOST} завершён"
