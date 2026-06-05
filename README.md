# java-context-service

Веб-сервис на Java 21 + Spring Boot для построения структурного контекста Java-классов по GitLab Merge Request с **сессией ревью** и pin коммитов source/target.

---

## Что делает сервис

1. **Создаёт сессию ревью** (`POST /api/review-sessions`): один раз получает MR, фиксирует `sourceSha` / `targetSha` / `baseSha` и снимок diff.
2. **Work-запросы** с `sessionId` в теле: контекст, PlantUML, исходники — без повторной передачи GitLab credentials.
3. **Терминирует сессию** (`DELETE /api/review-sessions`): отменяет in-flight задачи и удаляет данные из store.
4. Парсит изменённые файлы на **зафиксированных SHA** (source/target) через **JavaParser** и сравнивает структуры.
5. При `depth > 0` — волновой BFS зависимостей (репозиторий + `sources.jar`).
6. Группирует классы по `.java`-файлам в `FileContext`.

**Управление актуальностью MR** — на стороне оркестратора: при обновлении MR → `DELETE` старой сессии → `POST` новой. Сервис **не отслеживает** изменения MR автоматически.

### Типичный flow клиента

```text
1. POST /api/review-sessions  { gitlabUrl, projectId, token, mergeRequestIid }
   → sessionId, sourceSha, targetSha, expiresAt

2. POST /api/context          { "sessionId": "…", "depth": 0 }           // все изменённые файлы
   POST /api/context          { "sessionId": "…", "depth": 2, "names": ["com.example.Foo"] }
   POST /api/context/markdown  { "sessionId": "…", "depth": 2, "names": ["BarService"] }
   POST /api/source-file       { "sessionId": "…", "names": ["com.example.Foo"] }
   …

3. DELETE /api/review-sessions { "sessionId": "…" }
```

---

## Стек

| Компонент | Версия / библиотека |
|-----------|---------------------|
| Java | 21 (Gradle toolchain) |
| Spring Boot | 3.4.5 (`web`, `validation`) |
| gitlab4j-api | 6.0.0 |
| JavaParser | 3.26.3 |
| Caffeine | TTL-кэш сессий и файлового индекса GitLab |
| springdoc-openapi | 2.8.8 (Swagger UI) |
| Lombok | 1.18.36 |

---

## Запуск

```bash
./gradlew bootRun
```

Сервис: `http://localhost:8080`

**Swagger UI:** [http://localhost:8080/docs](http://localhost:8080/docs) — теги **Sessions**, **Structure**, **Sources**

---

## Конфигурация

| Параметр | По умолчанию | Описание |
|----------|--------------|----------|
| `server.port` | `8080` | Порт HTTP |
| `app.file-index-cache.expire-after-access-minutes` | `15` | TTL кэша raw-индекса (ключ: `gitlabUrl::projectId::ref`) |
| `app.parse-cache.enabled` | `true` | Кэш результатов парсинга |
| `app.parse-cache.dir` | `artifacts` | Каталог jar / disk parse-кэша |
| `app.parse-cache.expire-after-access-minutes` | `5` | TTL in-memory parse-кэша |
| `app.review-session.ttl-minutes` | `120` | TTL сессии (expireAfterWrite) |
| `app.review-session.uid-length` | `8` | Длина `sessionId` |
| `app.review-session.diff-refs-retry-attempts` | `3` | Retry при `diff_refs == null` |
| `app.review-session.diff-refs-retry-delay-ms` | `500` | Пауза между retry |
| `app.artifacts-dir` | `artifacts` | Каталог `sources.jar` |

---

## API

### Сессии (`ReviewSessionController`)

#### `POST /api/review-sessions`

```json
{
  "gitlabUrl": "https://gitlab.com",
  "projectId": "mygroup/myproject",
  "token": "glpat-xxxxxxxxxxxx",
  "mergeRequestIid": 42
}
```

**Ответ `201`:**

```json
{
  "sessionId": "k7Fm2xQp",
  "sourceSha": "e82eb4a0…",
  "targetSha": "1162f719…",
  "baseSha": "1162f719…",
  "expiresAt": "2026-06-05T14:00:00Z"
}
```

Повторный create на тот же MR терминирует предыдущую сессию.

#### `DELETE /api/review-sessions`

Тело: `{ "sessionId": "k7Fm2xQp" }` → `204`

---

### Структуры и UML (`StructureController`)

Все эндпоинты принимают `sessionId` и `depth`. Опционально `names` — корни обхода (simple/qualified/repo-путь `.java`); без `names` — все изменённые файлы MR.

Резолв `names`: сначала **repo-индекс**, затем **dependencySources** (sources.jar). Можно строить структуру от класса только из зависимости, в т.ч. при `depth=0`.

`dependencySources` загружаются **лениво** при `depth > 0`, при `names` вне repo-индекса или `/api/source-file`, и кэшируются в сессии.

```json
{
  "sessionId": "k7Fm2xQp",
  "depth": 2,
  "names": ["com.example.UserService", "src/main/java/com/example/Helper.java"]
}
```

| Метод | Путь | Тело | Ответ |
|-------|------|------|-------|
| POST | `/api/context` | `{ "sessionId", "depth", "names"? }` | `ContextResponse` |
| POST | `/api/context/html` | то же | HTML |
| POST | `/api/context/markdown` | то же | `List<String>` — `FileContext.toString()` на файл |
| POST | `/api/plantuml` | `{ "sessionId", "depth", "names"?, "pretty": true }` | `PlantUmlResponse` |
| POST | `/api/plantuml/text` | то же | `text/plain` |

---

### Исходники (`SourceController`)

#### `POST /api/source-lines/gitlab`

```json
{
  "session": { "sessionId": "k7Fm2xQp" },
  "classes": [
    { "qualifiedName": "com.example.Foo", "source": "main", "rows": ["28-168"] }
  ]
}
```

`ref` = pinned `sourceSha` из сессии.

#### `POST /api/source-file`

```json
{
  "sessionId": "k7Fm2xQp",
  "names": ["UserService", "com.example.Foo"]
}
```

`names` — simple или qualified имена. По каждому имени возвращает **все** совпадения в repo index и dependencySources.

#### `POST /api/source-lines/jar`

Без сессии (как раньше):

```json
{
  "source": "org.aspectj:aspectjweaver:1.9.22",
  "classes": [{ "qualifiedName": "org.aspectj.weaver.Advice", "rows": ["17"] }]
}
```

---

## Коды ошибок

| HTTP | Код | Когда |
|------|-----|-------|
| 404 | `SESSION_NOT_FOUND` | Сессия не найдена / TTL |
| 410 | `SESSION_TERMINATED` | Сессия терминирована (в т.ч. во время build) |
| 400 | — | `names` не найдены в индексе; пустой `names` |
| 422 | — | MR не `opened`/`locked` (только при create) |
| 503 | — | `diff_refs` не готов |

---

## Breaking change (v2)

Старый stateless-режим (GitLab credentials на каждый work-запрос) **удалён**. Клиент обязан использовать сессию; `depth` передаётся только на structure-эндпоинтах.

---

## Структура проекта

```
src/main/java/service/structure/
├── controller/
│   ├── ReviewSessionController.java   # Sessions
│   ├── StructureController.java       # Structure
│   ├── SourceController.java          # Sources
│   └── GlobalExceptionHandler.java
├── session/
│   ├── ReviewSession.java
│   ├── ReviewSessionService.java
│   ├── ReviewSessionResolver.java
│   └── ReviewSessionCancellation.java
├── service/
│   ├── ContextBuilderService.java
│   ├── FileSourceService.java
│   ├── GitLabService.java
│   └── ...
└── model/
    ├── CreateSessionRequest.java
    ├── SessionRequest.java
    ├── SessionIdRequest.java
    ├── CreateSessionResponse.java
    └── ...
```

---

## Тесты

```bash
./gradlew test
```

| Тест | Покрытие |
|------|----------|
| `ReviewSessionCancellationTest` | terminate + cancel futures |
| `ContextMarkdownFormatterTest` | `/api/context/markdown` |
| `GitLabServicePathTest` | findAllJavaPathsByName |
| `FileSourceServiceTest` | resolve по index |
| + существующие parser/context тесты |
