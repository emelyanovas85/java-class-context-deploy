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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.kalinin.context.model.ContextRequest;
import ru.kalinin.context.model.ContextResponse;
import ru.kalinin.context.model.GitLabLinesRequest;
import ru.kalinin.context.model.JarLinesRequest;
import ru.kalinin.context.model.SourceLinesResponse;
import ru.kalinin.context.service.ContextBuilderService;
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

                    `source` — Maven-координаты в формате `groupId:artifactId:version`,
                    точно соответствующие полю `source` в `ClassContext`
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
        log.info("Fetching jar source lines: source='{}', classes={}",
                request.source(), request.classes().size());
        return ResponseEntity.ok(sourceLinesService.fetchFromJar(request));
    }
}
