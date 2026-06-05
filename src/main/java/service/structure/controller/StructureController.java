package service.structure.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import service.structure.model.*;
import service.structure.service.ContextBuilderService;
import service.structure.service.ContextMarkdownFormatter;
import service.structure.service.HtmlContextRenderer;
import service.structure.service.PlantUmlRenderer;
import service.structure.session.ReviewSession;
import service.structure.session.ReviewSessionResolver;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Structure", description = """
        Построение структурного контекста Java-классов по сессии.

        Требует активную сессию (`POST /api/review-sessions`). Сравнивает source/target ветки MR,
        обходит зависимости (BFS) и группирует результат по `.java`-файлам.

        **Типичный сценарий:**
        1. `depth=0` без `names` — быстрый обзор всех изменённых файлов.
        2. `depth=N` с `names` — углублённый анализ конкретных классов (в т.ч. неизменённых или из jar).
        """)
public class StructureController {

    private static final String SESSION_REQUEST_HELP = """
            Тело запроса — `SessionRequest`:
            - `sessionId` — из ответа create
            - `depth` — глубина BFS (`0` = только корни, без обхода зависимостей)
            - `names` — опционально; без поля = все изменённые `.java` в MR

            `names` резолвится: repo-индекс → sources.jar. Jar подгружается при `depth>0`
            или если имя не найдено в repo (даже при `depth=0`).
            """;

    private final ReviewSessionResolver sessionResolver;
    private final ContextBuilderService contextBuilderService;
    private final HtmlContextRenderer htmlContextRenderer;
    private final PlantUmlRenderer plantUmlRenderer;

    @Operation(
            summary = "Построить контекст классов (JSON)",
            description = """
                    Основной эндпоинт. Возвращает `ContextResponse`: метаданные MR, список `FileContext`
                    (классы сравнены source vs target), `requestedDepth`, `totalClassesAnalyzed`.

                    """ + SESSION_REQUEST_HELP)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Контекст построен"),
            @ApiResponse(responseCode = "404", description = "Сессия не найдена"),
            @ApiResponse(responseCode = "410", description = "Сессия терминирована во время построения"),
            @ApiResponse(responseCode = "400", description = "Валидация или names не найдены в repo/jar")
    })
    @PostMapping("/context")
    public ResponseEntity<ContextResponse> getContext(@Valid @RequestBody SessionRequest request) {
        ReviewSession session = sessionResolver.requireActive(request.sessionId());
        log.info("Building context for session {}, depth={}, seeds={}",
                session.sessionId(), request.depth(), seedLabel(request.names()));
        Instant start = Instant.now();
        ContextResponse response = contextBuilderService.buildContext(
                session, request.depth(), request.names());
        log.info("Context built for session {}: {} classes   {} ms",
                session.sessionId(), response.totalClassesAnalyzed(),
                Duration.between(start, Instant.now()).toMillis());
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "Построить контекст (HTML)",
            description = """
                    То же, что `/api/context`, но ответ — HTML-страница для отладки и визуального просмотра
                    деревьев структур. Параметры запроса идентичны JSON-версии.

                    """ + SESSION_REQUEST_HELP)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "HTML-страница"),
            @ApiResponse(responseCode = "404", description = "Сессия не найдена"),
            @ApiResponse(responseCode = "410", description = "Сессия терминирована"),
            @ApiResponse(responseCode = "400", description = "Валидация или names не найдены")
    })
    @PostMapping(value = "/context/html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> getContextHtml(@Valid @RequestBody SessionRequest request) {
        ReviewSession session = sessionResolver.requireActive(request.sessionId());
        Instant start = Instant.now();
        ContextResponse response = contextBuilderService.buildContext(
                session, request.depth(), request.names());
        String html = htmlContextRenderer.render(response);
        log.info("HTML context built for session {} ({} classes)   {} ms",
                session.sessionId(), response.totalClassesAnalyzed(),
                Duration.between(start, Instant.now()).toMillis());
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    @Operation(
            summary = "Построить контекст (markdown)",
            description = """
                    То же, что `/api/context`, но ответ — JSON-массив строк: по одному `FileContext.toString()`
                    на каждый файл. Удобно для передачи в LLM без парсинга дерева JSON.

                    """ + SESSION_REQUEST_HELP)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Массив markdown-строк"),
            @ApiResponse(responseCode = "404", description = "Сессия не найдена"),
            @ApiResponse(responseCode = "410", description = "Сессия терминирована"),
            @ApiResponse(responseCode = "400", description = "Валидация или names не найдены")
    })
    @PostMapping("/context/markdown")
    public ResponseEntity<List<String>> getContextMarkdown(@Valid @RequestBody SessionRequest request) {
        ReviewSession session = sessionResolver.requireActive(request.sessionId());
        ContextResponse response = contextBuilderService.buildContext(
                session, request.depth(), request.names());
        return ResponseEntity.ok(ContextMarkdownFormatter.toMarkdownLines(response));
    }

    private static final String PLANTUML_REQUEST_HELP = """
            Тело запроса — `PlantUmlSessionRequest`:
            - `sessionId`, `depth`, `names` — как в `/api/context`
            - `pretty` — форматирование диаграммы (`true` по умолчанию)
            """;

    @Operation(
            summary = "Построить PlantUML class diagram (JSON)",
            description = """
                    Строит контекст (как `/api/context`), рендерит PlantUML class diagram.
                    Ответ: `plantUml` (текст диаграммы) + метаданные MR и счётчики.

                    """ + PLANTUML_REQUEST_HELP)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Диаграмма в JSON-обёртке"),
            @ApiResponse(responseCode = "404", description = "Сессия не найдена"),
            @ApiResponse(responseCode = "410", description = "Сессия терминирована"),
            @ApiResponse(responseCode = "400", description = "Валидация или names не найдены")
    })
    @PostMapping("/plantuml")
    public ResponseEntity<PlantUmlResponse> getPlantUml(@Valid @RequestBody PlantUmlSessionRequest request) {
        ReviewSession session = sessionResolver.requireActive(request.sessionId());
        boolean pretty = request.prettyOrDefault();
        ContextResponse context = contextBuilderService.buildContext(
                session, request.depth(), request.names());
        String plantUml = plantUmlRenderer.render(context, pretty);
        PlantUmlResponse response = new PlantUmlResponse(
                context.mergeRequest(),
                plantUml,
                pretty,
                context.requestedDepth(),
                context.totalClassesAnalyzed());
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "Построить PlantUML (plain text)",
            description = """
                    Аналог `/api/plantuml`, но тело ответа — только текст PlantUML (`text/plain`),
                    без JSON-обёртки. Удобно для копирования в plantuml.com или рендерер.

                    """ + PLANTUML_REQUEST_HELP)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Текст PlantUML"),
            @ApiResponse(responseCode = "404", description = "Сессия не найдена"),
            @ApiResponse(responseCode = "410", description = "Сессия терминирована"),
            @ApiResponse(responseCode = "400", description = "Валидация или names не найдены")
    })
    @PostMapping(value = "/plantuml/text", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getPlantUmlText(@Valid @RequestBody PlantUmlSessionRequest request) {
        ReviewSession session = sessionResolver.requireActive(request.sessionId());
        boolean pretty = request.prettyOrDefault();
        ContextResponse context = contextBuilderService.buildContext(
                session, request.depth(), request.names());
        String plantUml = plantUmlRenderer.render(context, pretty);
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(plantUml);
    }

    private static String seedLabel(List<String> names) {
        return names == null ? "changed-files" : String.valueOf(names.size());
    }
}
