# Java Class Context MCP Server (обёртка)

MCP-сервер на **Spring Boot + Spring AI**, который оборачивает основной сервис
[Java Class Context API](../README.md) и публикует все его REST-эндпоинты как **MCP tools**.

- **Транспорт:** SSE (Server-Sent Events), поверх Spring MVC.
- **Деплой:** Docker, по аналогии с основным сервисом. По умолчанию — `10.1.5.97:8086`.
- **Основной сервис (upstream):** `10.1.5.97:8084` (вызывается по HTTP API).

```
┌────────────┐     MCP (SSE)      ┌─────────────────────┐    HTTP/REST     ┌──────────────────────┐
│ MCP-клиент │ ◀───────────────▶ │  MCP-обёртка :8086  │ ───────────────▶ │ Java Class Context   │
│ (LLM)      │                    │  (этот модуль)      │                  │ API :8084 (основной) │
└────────────┘                    └─────────────────────┘                  └──────────────────────┘
```

## Стек

- Java 21, Spring Boot 3.4.5 (как в основном проекте)
- Spring AI 1.0.0 (`spring-ai-starter-mcp-server-webmvc`)
- Gradle (wrapper)

## MCP tools

Покрыты все 8 эндпоинтов основного API (10 инструментов).

| Tool | Эндпоинт upstream | Сессия | `names` |
|------|-------------------|--------|---------|
| `create_review_session` | `POST /api/review-sessions` | создаёт | — |
| `terminate_review_session` | `DELETE /api/review-sessions` | использует | — |
| `get_structure_json` | `POST /api/structure/json` | требует | опционально |
| `get_structure_markdown` | `POST /api/structure/markdown` | требует | опционально |
| `get_structure_html` | `POST /api/structure/html` | требует | опционально |
| `get_plantuml_object` | `POST /api/structure/plantuml/object` | требует | опционально |
| `get_plantuml_text` | `POST /api/structure/plantuml/text` | требует | опционально |
| `get_source_file` | `POST /api/source-file` | требует | обязателен |
| `get_source_lines_gitlab` | `POST /api/source-lines/gitlab` | требует | classes |
| `get_source_lines_jar` | `POST /api/source-lines/jar` | **не требует** | classes |

Если `names` не указан (или пустой), structure-инструменты анализируют **все изменённые `.java` MR** —
обёртка не отправляет поле `names` в upstream, сохраняя семантику основного API.

## Эндпоинты сервера

| URL | Назначение |
|-----|------------|
| `GET /sse` | SSE-подключение MCP-клиента |
| `POST /mcp/message` | приём сообщений MCP-клиента |
| `GET /info` | health/info (используется деплой-скриптом) |
| `GET /docs` | Swagger UI |
| `GET /api-docs` | OpenAPI JSON |

## Конфигурация

| Переменная окружения | По умолчанию | Описание |
|----------------------|--------------|----------|
| `APP_UPSTREAM_BASE_URL` | `http://10.1.5.97:8084` | базовый URL основного сервиса |
| `APP_UPSTREAM_CONNECT_TIMEOUT` | `5000` | таймаут соединения, мс |
| `APP_UPSTREAM_READ_TIMEOUT` | `120000` | таймаут чтения, мс (учёт фонового построения индекса) |
| `LOGGING_LEVEL_SERVICE_MCP` | `INFO` | уровень логирования модуля |

## Локальный запуск

```bash
./gradlew bootRun
# или
./gradlew bootJar && java -jar build/libs/*.jar
```

С указанием адреса основного сервиса:

```bash
APP_UPSTREAM_BASE_URL=http://10.1.5.97:8084 ./gradlew bootRun
```

## Деплой

По аналогии с `deploy-java-class-context.sh` основного сервиса. Образ собирается локально и
передаётся на сервер через SSH (без доступа в интернет на сервере):

```bash
# из каталога mcp-wrapper
chmod +x deploy-mcp-wrapper.sh
./deploy-mcp-wrapper.sh
# с явным адресом upstream и портом:
./deploy-mcp-wrapper.sh --upstream-url http://10.1.5.97:8084 --app-port 8086
```

Скрипт:
1. собирает Docker-образ `java-class-context-mcp:latest` локально;
2. передаёт его на `10.1.5.97` (`docker save | ssh docker load`);
3. передаёт исходники модуля (`git archive <branch>:mcp-wrapper`);
4. настраивает внешний порт `8086 → 8080` и `APP_UPSTREAM_BASE_URL` в `.env`;
5. запускает контейнер и ждёт готовности по `GET /info`.

## Тесты

```bash
./gradlew test
```
