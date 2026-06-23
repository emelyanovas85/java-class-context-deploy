#!/usr/bin/env bash
# =============================================================================
# apply-patch.sh — применяет патч tools.py внутри контейнера open-webui
#
# Проблема: Open WebUI вызывает tools/list ТОЛЬКО если сервер вернул
# заголовок Mcp-Session-Id. Supergateway (stateless MCP) его не возвращает,
# поэтому список инструментов всегда пустой.
#
# Фикс: заменяем `if session_id:` → `if True:` чтобы tools/list
# вызывался всегда, независимо от наличия session_id.
#
# Использование:
#   ./apply-patch.sh [КОНТЕЙНЕР]
#
#   По умолчанию контейнер: open-webui
#
# Примеры:
#   ./apply-patch.sh
#   ./apply-patch.sh open-webui
# =============================================================================

set -euo pipefail

CONTAINER="${1:-open-webui}"
TARGET="/app/backend/open_webui/utils/tools.py"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
ok()   { echo -e "${GREEN}✓${NC} $*"; }
warn() { echo -e "${YELLOW}⚠${NC} $*"; }
fail() { echo -e "${RED}✗${NC} $*" >&2; exit 1; }

# Проверяем что контейнер существует
docker inspect "${CONTAINER}" > /dev/null 2>&1 \
  || fail "Контейнер '${CONTAINER}' не найден"

# Проверяем — не применён ли патч уже
if docker exec "${CONTAINER}" grep -q 'patched: always call tools/list' "${TARGET}" 2>/dev/null; then
  warn "Патч уже применён в контейнере '${CONTAINER}' — пропускаем"
  exit 0
fi

# Проверяем наличие строки для замены
docker exec "${CONTAINER}" grep -q 'if session_id:' "${TARGET}" \
  || fail "Строка 'if session_id:' не найдена в ${TARGET}. Версия Open WebUI изменилась?"

# Применяем патч
docker exec "${CONTAINER}" sed -i \
  's/        if session_id:$/        if True:  # patched: always call tools\/list (stateless MCP)/' \
  "${TARGET}"

# Проверяем результат
docker exec "${CONTAINER}" grep -q 'patched: always call tools/list' "${TARGET}" \
  || fail "Патч не применился — проверьте вручную"

ok "Патч применён в контейнере '${CONTAINER}'"
echo ""
echo "Перезапустите процесс Open WebUI для применения:"
echo "  docker restart ${CONTAINER}"
