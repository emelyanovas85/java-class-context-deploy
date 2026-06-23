# open-webui

Настройка и патчи для контейнера [Open WebUI](https://github.com/open-webui/open-webui).

## Патч: stateless MCP (tools/list без session_id)

### Проблема

Open WebUI вызывает `tools/list` **только если** MCP-сервер вернул заголовок `Mcp-Session-Id`.
Supergатeway работает в stateless-режиме и этот заголовок не возвращает.
В результате список инструментов всегда пустой.

**Затронутый код** (`open_webui/utils/tools.py`, функция `_mcp_streamable_initialize`):
```python
# ДО (баг): tools/list не вызывается без session_id
if session_id:
    list_headers["Mcp-Session-Id"] = session_id
    # ... tools/list ...

# ПОСЛЕ (фикс): tools/list вызывается всегда
if True:  # patched: always call tools/list (stateless MCP)
    # session_id может быть пустым — это нормально для stateless серверов
    if session_id:
        list_headers["Mcp-Session-Id"] = session_id
    # ... tools/list ...
```

### Применение патча

```bash
cd open-webui
chmod +x apply-patch.sh
./apply-patch.sh           # контейнер по умолчанию: open-webui
./apply-patch.sh my-webui  # или указать имя контейнера явно
```

После этого перезапустить контейнер:
```bash
docker restart open-webui
```

### Проверка

```bash
# Убедиться что патч применён
docker exec open-webui grep -n 'patched\|if session_id' \
  /app/backend/open_webui/utils/tools.py
```

### Важно

Патч **слетит** при пересоздании контейнера (`docker pull` + `docker compose up`).
После каждого обновления образа Open WebUI нужно применять патч повторно:

```bash
./apply-patch.sh
docker restart open-webui
```
