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
import ru.kalinin.context.model.SourceLinesRequest;
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
            @ApiResponse(
                    responseCode = "400",
                    description = "Ошибка валидации входящего запроса",
                    content = @Content(schema = @Schema())
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Ошибка обращения к GitLab API или парсинга",
                    content = @Content(schema = @Schema())
            )
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
            summary = "Получить исходный код строк",
            description = """
                    Для каждого переданного файла читает исходник из GitLab и возвращает
                    содержимое указанных диапазонов строк.

                    Формат диапазона — тот же, что в `rows` у `StructureNode`:
                    `"17"` (1 строка) или `"19-22"` (диапазон, включительно).

                    Если файл не найден или недоступен, в ответе для него будет заполнено
                    поле `error` вместо `snippets`.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Строки получены (ошибки по отдельным файлам возвращаются в теле ответа)",
                    content = @Content(schema = @Schema(implementation = SourceLinesResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Ошибка валидации",
                    content = @Content(schema = @Schema())
            )
    })
    @PostMapping("/source-lines")
    public ResponseEntity<SourceLinesResponse> getSourceLines(
            @Valid @RequestBody SourceLinesRequest request) {
        log.info("Fetching source lines: project='{}', ref='{}', files={}",
                request.projectId(), request.ref(), request.files().size());
        SourceLinesResponse response = sourceLinesService.fetchLines(request);
        return ResponseEntity.ok(response);
    }
}
