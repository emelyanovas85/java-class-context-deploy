# java-context-service

Веб-сервис на Java 21 + Spring Boot для построения структурного контекста Java-классов по GitLab Merge Request: сравнение source/target веток, рекурсивный обход зависимостей (включая внешние `sources.jar`) и выдача фрагментов исходного кода по номерам строк.

---

## Что делает сервис

1. Принимает параметры MR (URL GitLab, токен, ID проекта, IID реквеста, глубина контекста).
2. Проверяет, что MR в состоянии `opened` или `locked` (смерженные и закрытые MR не анализируются).
3. Получает метаданные MR через **gitlab4j-api**: ветки, коммиты, diff, список изменённых `.java`-файлов.
4. Строит **мёрженный файловый индекс** репозитория (target-ветка + патч из diff MR) для резолвинга типов по qualified name.
5. Парсит изменённые файлы на **обеих ветках** (source и target) через **JavaParser** и сравнивает структуры.
6. Для каждого класса возвращает **дерево `StructureNode`**: сигнатуры с аннотациями, поля, методы, конструкторы, вложенные типы — с диапазонами строк (`rows`).
7. Если `depth > 1`: волновым BFS находит упомянутые типы (поля, параметры, возвраты, исключения, bounds дженериков, аннотации), резолвит их в репозитории или во **внешних зависимостях** (Maven/Gradle → Artifactory → `*-sources.jar`).
8. Строит **граф зависимостей**: у каждого класса есть сквозной `id` и множество `callerIds` (кто на него ссылается).
9. Группирует классы по `.java`-файлам в `FileContext`.
10. Дополнительно: HTML-страница для отладки и эндпоинты для выборки строк исходников по `rows` из GitLab или локального jar.

---

## Стек

| Компонент | Версия / библиотека |
|-----------|---------------------|
| Java | 21 (Gradle toolchain) |
| Spring Boot | 3.4.5 (`web`, `validation`) |
| gitlab4j-api | 6.0.0 |
| JavaParser | 3.26.3 |
| Caffeine | TTL-кэш файлового индекса GitLab |
| springdoc-openapi | 2.8.8 (Swagger UI) |
| Lombok | 1.18.36 |

---

## Запуск

```bash
./gradlew bootRun
```

Сервис доступен на `http://localhost:8080`.

**Swagger UI:** [http://localhost:8080/docs](http://localhost:8080/docs)  
**OpenAPI JSON:** [http://localhost:8080/api-docs](http://localhost:8080/api-docs)

---

## Конфигурация

Основные параметры в `src/main/resources/application.yml` и `application.properties`:

| Параметр | По умолчанию | Описание |
|----------|--------------|----------|
| `server.port` | `8080` | Порт HTTP |
| `app.file-index-cache.expire-after-access-minutes` | `15` | TTL кэша raw-индекса файлов GitLab |
| `app.parse-cache.enabled` | `true` | Включить кэш результатов парсинга |
| `app.parse-cache.dir` | `artifacts` | Каталог для jar, `.module` и дискового parse-кэша |
| `app.parse-cache.expire-after-access-minutes` | `5` | TTL in-memory parse-кэша (Caffeine) |
| `app.artifacts-dir` | `artifacts` | Каталог для скачанных `sources.jar` (используется `SourceLinesService`) |

---

## API

### `POST /api/context`

Строит JSON-контекст по MR.

**Request body:**

```json
{
  "gitlabUrl": "https://gitlab.com",
  "projectId": "mygroup/myproject",
  "token": "glpat-xxxxxxxxxxxx",
  "mergeRequestIid": 42,
  "depth": 2
}
```

| Поле | Тип | Описание |
|------|-----|----------|
| `gitlabUrl` | String | URL GitLab-инстанса (без слэша в конце) |
| `projectId` | String | ID проекта или `namespace/name` |
| `token` | String | Personal Access Token или Project Access Token |
| `mergeRequestIid` | Long | IID мёрж-реквеста (внутренний номер в проекте) |
| `depth` | int ≥ 1 | Глубина: `1` — только изменённые файлы MR, `2+` — + зависимости |

**Ответ (`ContextResponse`):**

```json
{
  "mergeRequest": { "iid": 42, "title": "...", "state": "opened", "sourceBranch": "...", "targetBranch": "...", "..." : "..." },
  "files": [
    {
      "path": "src/main/java/com/example/Foo.java",
      "module": "src/main",
      "level": 0,
      "classes": [
        {
          "kind": "modified",
          "id": 1,
          "name": "com.example.Foo",
          "level": 0,
          "callerIds": [],
          "module": "src/main",
          "structureSource": [ { "type": "class", "signature": "...", "rows": "10-45", "children": [ "..." ] } ],
          "structureTarget": [ "..." ]
        }
      ]
    }
  ],
  "requestedDepth": 2,
  "totalClassesAnalyzed": 15
}
```

**Семантика полей:**

- **`level`** — `0` = класс из изменённого файла MR; `1` = прямая зависимость; `2` = зависимость зависимости и т.д.
- **`module`** — источник класса: `src/main`, `src/test` или Maven-координаты `groupId:artifactId:version` для внешней зависимости.
- **`kind`** — `unchanged` (структуры source/target совпали) или `modified` (различаются, либо файл создан/удалён).
- **`id` / `callerIds`** — граф ссылок между классами в рамках одного ответа.
- **`StructureNode`** — рекурсивное дерево: `type` (`class`, `interface`, `enum`, `record`, `annotation`, `field`, `method`, `constructor`), `signature`, `rows` (`"17"` или `"19-22"`), `children`.

---

### `POST /api/context/html`

Те же параметры, что у `/api/context`. Возвращает HTML-страницу для отладки (шаблон `context-debug.html` + встроенный JSON контекста).

---

### `POST /api/source-lines/gitlab`

Читает строки из GitLab-репозитория по диапазонам из `StructureNode.rows`.

```json
{
  "gitlabUrl": "https://gitlab.com",
  "projectId": "mygroup/myproject",
  "token": "glpat-xxxxxxxxxxxx",
  "ref": "main",
  "classes": [
    {
      "qualifiedName": "com.example.Foo",
      "source": "main",
      "rows": ["28-168", "40"]
    }
  ]
}
```

| Поле | Описание |
|------|----------|
| `ref` | Ветка или коммит |
| `classes[].source` | `"main"` \| `"test"` \| `null` — уточняет поиск при коллизии одноимённых классов |
| `classes[].rows` | Диапазоны строк, как в `StructureNode.rows` |

Ошибки по отдельным классам возвращаются в теле ответа (HTTP 200) в поле `error` вместо `snippets`.

---

### `POST /api/source-lines/jar`

Читает строки из локального `*-sources.jar` (скачанного сервисом в `artifacts/`).

```json
{
  "source": "org.aspectj:aspectjweaver:1.9.22",
  "classes": [
    {
      "qualifiedName": "org.aspectj.weaver.Advice",
      "rows": ["17", "19-22"]
    }
  ]
}
```

| Поле | Описание |
|------|----------|
| `source` | Maven-координаты — то же значение, что `module` у `ClassContext` для внешней зависимости |

---

## Алгоритм построения контекста

```
1. MR metadata + проверка state ∈ {opened, locked}
2. Raw file index (target branch, TTL-кэш) → merged index (+ diff MR)
3. Зависимости: pom.xml / *.gradle → Artifactory → sources.jar + .module (BFS, параллельно)
4. Уровень 0:
     changedFiles → fetch source + target → parse → merge → ClassContext[]
5. Уровни 1..depth-1 (волновой BFS):
     collectReferencedTypes() → resolve (repo index | jar index)
     → parse (JavaSourceParseService) → merge → следующая волна
6. Финальный пасс: нерезолвленные типы
7. groupContextsByFile() → List<FileContext>
```

Parse-кэш: in-memory (Caffeine, scope на запрос) + диск (`{module}__cache.json` для jar-модулей).

---

## Выбор библиотеки для парсинга Java

| Библиотека | Плюсы | Минусы | Вердикт |
|------------|-------|--------|---------|
| **JavaParser** | Простой API, нет внешних зависимостей, активная разработка, поддержка Java 21+ | Нет семантического анализа без symbol solver | ✅ Выбрана |
| **Spoon** | Семантический анализ, трансформации AST | Тяжёлый, строит на Eclipse JDT, сложная интеграция | Избыточен для задачи |
| **Eclipse JDT** | Компилятор, полный семантический анализ | OSGi-зависимости, сложная настройка без Eclipse | Слишком сложен |
| **QDox** | Быстрый, маленький, ориентирован на сигнатуры | Ограниченный AST, нет аннотаций на параметрах | Недостаточно богат |

**Итог:** JavaParser даёт полный AST с аннотациями, лёгкий в использовании и достаточен для извлечения структуры без компиляции.

---

## Структура проекта

```
src/main/java/ru/kalinin/context/
├── JavaContextServiceApplication.java
├── cache/
│   ├── ClassContextParseCache.java       # memory + disk parse-кэш
│   ├── ParseCacheRequestScope.java
│   ├── ParseCacheEntry.java
│   └── ParseCacheDiskFile.java
├── config/
│   ├── AsyncConfig.java                  # ioExecutor для параллельного I/O
│   ├── CacheConfig.java                  # Caffeine-бины
│   └── OpenApiConfig.java
├── controller/
│   ├── ContextController.java            # REST API
│   └── GlobalExceptionHandler.java
├── dependency/
│   ├── ArtifactorySourcesLoader.java     # скачивание sources.jar / .module
│   ├── DependencyClassNameExtractor.java
│   ├── DependencyContextService.java     # оркестрация зависимостей
│   ├── DependencyCoordinate.java
│   ├── DependencyExtractor.java
│   ├── GradleDependencyExtractor.java
│   └── MavenDependencyExtractor.java
├── exception/
│   └── MergeRequestAlreadyMergedException.java
├── model/
│   ├── ClassContext.java                 # sealed: unchanged | modified
│   ├── UnchangedClassContext.java
│   ├── ModifiedClassContext.java
│   ├── StructureNode.java                # рекурсивное дерево структуры
│   ├── StructureNodePrinter.java
│   ├── FileContext.java                  # группировка классов по файлу
│   ├── ContextRequest.java / ContextResponse.java
│   ├── GitLabLinesRequest.java / JarLinesRequest.java
│   ├── SourceLinesResponse.java
│   ├── MergeRequestInfo.java / CommitInfo.java
│   └── ClassStructure.java, FieldInfo, MethodInfo, ...  # внутренние модели парсера
├── parser/
│   ├── JavaStructureParser.java
│   ├── JavaSourceParseService.java
│   ├── StructureNodeMapper.java
│   ├── SignatureBuilder.java
│   ├── JavaLangTypeRegistry.java
│   ├── ParsedJavaFile.java
│   └── UnresolvedTypeRef.java
└── service/
    ├── ContextBuilderService.java        # оркестрация построения контекста
    ├── DependencyContextService.java
    ├── GitLabService.java                # GitLab API + file index
    ├── HtmlContextRenderer.java          # HTML для /api/context/html
    └── SourceLinesService.java           # /api/source-lines/*

src/main/resources/
├── application.yml
├── application.properties
├── templates/context-debug.html
└── static/context-debug.{js,css}
```

---

## Тесты

```bash
./gradlew test
```

| Тест | Что покрывает |
|------|---------------|
| `JavaStructureParserTest` | Парсинг class/record/nested, полей, методов, конструкторов, аннотаций, referenced types |
| `StructureNodeMapperTest` | Маппинг AST → `StructureNode`, диапазоны строк |
| `JavaSourceParseServiceTest` | Парсинг файлов, кэширование на уровне сервиса |
| `ClassContextParseCacheTest` | Memory/disk parse-кэш |
| `ContextBuilderGroupByFileTest` | Группировка классов по файлам |
| `ContextBuilderMergeResultsTest` | Merge source/target структур |
| `ContextBuilderNestedWaveTest` | Волновой BFS по зависимостям |
| `HtmlContextRendererTest` | HTML-рендер контекста |
