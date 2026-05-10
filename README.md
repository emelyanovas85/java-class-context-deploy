# java-context-service

Веб-сервис на Java 25 + Spring Boot для построения структурного контекста классов из GitLab Merge Request.

---

## Что делает сервис

1. Принимает запрос с параметрами MR (URL GitLab, токен, ID проекта, IID реквеста, глубина контекста).
2. Получает метаданные MR через **gitlab4j-api**: ветки, коммиты, список изменённых файлов.
3. Читает содержимое `.java`-файлов из GitLab и парсит их через **JavaParser**.
4. Для каждого класса строит **структуру**: сигнатура класса, все поля, методы, конструкторы, вложенные классы — с аннотациями.
5. Если `depth > 1`: находит все упомянутые типы (параметры, возвращаемые типы, исключения, ограничения дженериков, аннотации), ищет их в репозитории GitLab, парсит — повторяет для каждого уровня.

---

## Выбор библиотеки для парсинга Java

| Библиотека        | Плюсы                                                   | Минусы                                     | Вердикт |
|-------------------|---------------------------------------------------------|--------------------------------------------|----------|
| **JavaParser**    | Простой API, нет внешних зависимостей, активная разработка, поддержка Java 21+, 535+ usages на Maven | Нет семантического анализа без symbol solver | ✅ Выбрана |
| **Spoon**         | Семантический анализ, трансформации AST                 | Тяжёлый, строит на Eclipse JDT, сложная интеграция | Избыточен для задачи |
| **Eclipse JDT**   | Компилятор, полный семантический анализ                 | OSGi-зависимости, сложная настройка без Eclipse | Слишком сложен |
| **QDox**          | Быстрый, маленький, ориентирован на сигнатуры           | Ограниченный AST, нет аннотаций на параметрах | Недостаточно богат |

**Итог**: JavaParser даёт полный AST с аннотациями, лёгкий в использовании и достаточен для извлечения структуры без компиляции.

---

## Стек

- **Java 25**
- **Gradle**
- **Spring Boot 3.4.1** (`spring-boot-starter-web`, `spring-boot-starter-validation`)
- **gitlab4j-api 6.0.0** — GitLab REST API клиент
- **JavaParser 3.26.3** — парсинг Java AST
- **Lombok** — устранение бойлерплейта

---

## Запуск

```bash
./gradlew bootRun
```

Сервис запустится на `http://localhost:8080`.

---

## API

### `POST /api/context`

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

**Поля:**
| Поле              | Тип     | Описание                                               |
|-------------------|---------|--------------------------------------------------------|
| `gitlabUrl`       | String  | URL GitLab-инстанса (без слэша в конце)               |
| `projectId`       | String  | ID проекта или `namespace/name`                        |
| `token`           | String  | Personal Access Token или Project Access Token         |
| `mergeRequestIid` | Long    | IID мёрж-реквеста (внутренний номер в проекте)        |
| `depth`           | int ≥ 1 | Глубина анализа зависимостей (1 = только изменённые файлы) |

**`contextLevel` в ответе:**
- `0` — класс из изменённого файла MR
- `1` — прямая зависимость (тип из Level 0)
- `2` — зависимость зависимости, и т.д.

---

## Алгоритм построения контекста

```
Уровень 0:
  changedFiles → читаем из GitLab → парсим → ClassStructure[]

Уровень N (1..depth-1):
  ClassStructure[N-1] → collectReferencedTypes()
    └── типы из: аннотаций, полей, параметров, возвращаемых типов,
                 исключений, ограничений дженериков, расширений
  → findJavaFileByQualifiedName() → читаем из GitLab → парсим → ClassStructure[]
```

---

## Структура проекта

```
src/main/java/ru/kalinin/context/
├── JavaContextServiceApplication.java
├── controller/
│   ├── ContextController.java        # POST /api/context
│   └── GlobalExceptionHandler.java   # обработка ошибок
├── service/
│   ├── ContextBuilderService.java    # оркестрация построения контекста
│   └── GitLabService.java            # работа с GitLab API
├── parser/
│   └── JavaStructureParser.java      # парсинг Java через JavaParser
└── model/
    ├── ContextRequest.java
    ├── ContextResponse.java
    ├── MergeRequestInfo.java
    ├── CommitInfo.java
    ├── ClassStructure.java
    ├── FieldInfo.java
    ├── MethodInfo.java
    ├── ParameterInfo.java
    └── AnnotationInfo.java
```

---

## Тесты

```bash
./gradlew test
```

`JavaStructureParserTest` покрывает: парсинг class/record/nested классов, полей, методов, конструкторов, аннотаций, сбор referenced types.
