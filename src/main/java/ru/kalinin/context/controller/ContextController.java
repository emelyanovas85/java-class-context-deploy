package ru.kalinin.context.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.kalinin.context.model.ContextRequest;
import ru.kalinin.context.model.ContextResponse;
import ru.kalinin.context.model.GitLabLinesRequest;
import ru.kalinin.context.model.JarLinesRequest;
import ru.kalinin.context.model.PlantUmlRequest;
import ru.kalinin.context.model.PlantUmlResponse;
import ru.kalinin.context.model.SourceLinesResponse;
import ru.kalinin.context.service.ContextBuilderService;
import ru.kalinin.context.service.HtmlContextRenderer;
import ru.kalinin.context.service.PlantUmlRenderer;
import ru.kalinin.context.service.SourceLinesService;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Context", description = "Построение структурного контекста классов по MR GitLab")
public class ContextController {

    private final ContextBuilderService contextBuilderService;
    private final HtmlContextRenderer htmlContextRenderer;
    private final PlantUmlRenderer plantUmlRenderer;
    private final SourceLinesService sourceLinesService;

    // -------------------------------------------------------------------------
    // Context
    // -------------------------------------------------------------------------

    @Operation(
            summary = "Построить контекст классов",
            description = """
                    Получает список изменённых файлов из мёрж-реквеста GitLab,
                    парсит Java-исходники и возвращает структуру каждого класса
                    (сигнатуры полей, методов, вложенных классов — всё с аннотациями).

                    Если `depth` > 1, рекурсивно добавляет структуры зависимых классов:
                    типы полей, параметры методов, возвращаемые типы, исключения,
                    bounds дженериков и аннотации.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Контекст успешно построен",
                    content = @Content(schema = @Schema(implementation = ContextResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "Ошибка валидации",
                    content = @Content(schema = @Schema())),
            @ApiResponse(responseCode = "500", description = "Ошибка GitLab API или парсинга",
                    content = @Content(schema = @Schema()))
    })
    @PostMapping("/context")
    public ResponseEntity<ContextResponse> getContext(@Valid @RequestBody ContextRequest request) {
        log.info("Building context for MR !{} in project '{}', depth={}",
                request.mergeRequestIid(), request.projectId(), request.depth());
        Instant start = Instant.now();
        ContextResponse response = contextBuilderService.buildContext(request);
        log.info("Context built: {} classes analyzed   {} ms",
                response.totalClassesAnalyzed(), Duration.between(start, Instant.now()).toMillis());
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "Построить контекст (HTML для отладки)",
            description = """
                    Те же параметры, что у POST /api/context. Возвращает HTML со встроенным
                    контекстом и спиннером на время отрисовки на клиенте.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "HTML-страница с контекстами"),
            @ApiResponse(responseCode = "400", description = "Ошибка валидации",
                    content = @Content(schema = @Schema())),
            @ApiResponse(responseCode = "500", description = "Ошибка GitLab API или парсинга",
                    content = @Content(schema = @Schema()))
    })
    @PostMapping(value = "/context/html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> getContextHtml(@Valid @RequestBody ContextRequest request) {
        log.info("Building HTML debug context for MR !{} in project '{}', depth={}",
                request.mergeRequestIid(), request.projectId(), request.depth());
        Instant start = Instant.now();
        ContextResponse response = contextBuilderService.buildContext(request);
        String html = htmlContextRenderer.render(response);
        log.info("HTML context built for MR !{} ({} classes)   {} ms",
                request.mergeRequestIid(), response.totalClassesAnalyzed(),
                Duration.between(start, Instant.now()).toMillis());
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }

    @Operation(
            summary = "Построить PlantUML class diagram",
            description = """
                    Параметры MR в поле `context` (как у POST /api/context) и опционально `pretty`.
                    Строит контекст классов MR и возвращает PlantUML class diagram.
                    `pretty=true` (по умолчанию) — с отступами; `pretty=false` — компактный текст.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "PlantUML успешно построен",
                    content = @Content(schema = @Schema(implementation = PlantUmlResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "Ошибка валидации",
                    content = @Content(schema = @Schema())),
            @ApiResponse(responseCode = "500", description = "Ошибка GitLab API или парсинга",
                    content = @Content(schema = @Schema()))
    })
    @PostMapping("/plantuml")
    public ResponseEntity<PlantUmlResponse> getPlantUml(@Valid @RequestBody PlantUmlRequest request) {
        ContextRequest ctx = request.context();
        boolean pretty = request.prettyOrDefault();
        log.info("Building PlantUML for MR !{} in project '{}', depth={}, pretty={}",
                ctx.mergeRequestIid(), ctx.projectId(), ctx.depth(), pretty);
        Instant start = Instant.now();
        ContextResponse context = contextBuilderService.buildContext(ctx);
        String plantUml = plantUmlRenderer.render(context, pretty);
        PlantUmlResponse response = new PlantUmlResponse(
                context.mergeRequest(),
                plantUml,
                pretty,
                context.requestedDepth(),
                context.totalClassesAnalyzed());
        log.info("PlantUML built: {} classes   {} ms",
                response.totalClassesAnalyzed(), Duration.between(start, Instant.now()).toMillis());
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "Построить PlantUML (plain text)",
            description = """
                    Те же параметры, что у POST /api/plantuml. Возвращает только текст
                    диаграммы (Content-Type: text/plain).
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Текст PlantUML"),
            @ApiResponse(responseCode = "400", description = "Ошибка валидации",
                    content = @Content(schema = @Schema())),
            @ApiResponse(responseCode = "500", description = "Ошибка GitLab API или парсинга",
                    content = @Content(schema = @Schema()))
    })
    @PostMapping(value = "/plantuml/text", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getPlantUmlText(@Valid @RequestBody PlantUmlRequest request) {
        ContextRequest ctx = request.context();
        boolean pretty = request.prettyOrDefault();
        log.info("Building PlantUML text for MR !{} in project '{}', depth={}, pretty={}",
                ctx.mergeRequestIid(), ctx.projectId(), ctx.depth(), pretty);
        Instant start = Instant.now();
        ContextResponse context = contextBuilderService.buildContext(ctx);
        String plantUml = plantUmlRenderer.render(context, pretty);
        log.info("PlantUML text built for MR !{} ({} classes)   {} ms",
                ctx.mergeRequestIid(), context.totalClassesAnalyzed(),
                Duration.between(start, Instant.now()).toMillis());
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(plantUml);
    }

    // -------------------------------------------------------------------------
    // Source lines — GitLab
    // -------------------------------------------------------------------------

    @Operation(
            summary = "Получить строки из файлов GitLab",
            description = """
                    Читает исходники из GitLab-репозитория и возвращает содержимое
                    указанных диапазонов строк.

                    Формат диапазона совпадает с полем `rows` у `StructureNode`:
                    `"17"` (одна строка) или `"19-22"` (включительно с обеих сторон).

                    Ошибки по отдельным файлам возвращаются в теле ответа (HTTP 200)
                    в поле `error` вместо `snippets`.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Строки получены (ошибки по файлам — в теле)",
                    content = @Content(schema = @Schema(implementation = SourceLinesResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "Ошибка валидации",
                    content = @Content(schema = @Schema()))
    })
    @PostMapping("/source-lines/gitlab")
    public ResponseEntity<SourceLinesResponse> getGitLabLines(@Valid @RequestBody GitLabLinesRequest request) {
        log.info("Fetching GitLab source lines: project='{}', ref='{}', classes={}",
                request.projectId(), request.ref(), request.classes().size());
        return ResponseEntity.ok(sourceLinesService.fetchFromGitLab(request));
    }

    // -------------------------------------------------------------------------
    // Source lines — Jar
    // -------------------------------------------------------------------------

    @Operation(
            summary = "Получить строки из sources.jar зависимости",
            description = """
                    Читает исходники из локального `*-sources.jar` и возвращает
                    содержимое указанных диапазонов строк.

                    `module` — Maven-координаты в формате `groupId:artifactId:version`,
                    точно соответствующие полю `module` в `ClassContext`
                    (например `org.aspectj:aspectjweaver:1.9.22`).

                    `qualifiedName` — полное имя класса внутри jar
                    (например `org.aspectj.weaver.Advice`).

                    Формат диапазона совпадает с полем `rows` у `StructureNode`:
                    `"17"` или `"19-22"`.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Строки получены (ошибки по классам — в теле)",
                    content = @Content(schema = @Schema(implementation = SourceLinesResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "Ошибка валидации",
                    content = @Content(schema = @Schema()))
    })
    @PostMapping("/source-lines/jar")
    public ResponseEntity<SourceLinesResponse> getJarLines(@Valid @RequestBody JarLinesRequest request) {
        log.info("Fetching jar source lines: module='{}', classes={}",
                request.source(), request.classes().size());
        return ResponseEntity.ok(sourceLinesService.fetchFromJar(request));
    }
}
