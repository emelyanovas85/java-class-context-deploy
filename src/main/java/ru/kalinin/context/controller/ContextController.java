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
import ru.kalinin.context.service.ContextBuilderService;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Context", description = "Построение структурного контекста классов по MR GitLab")
public class ContextController {

    private final ContextBuilderService contextBuilderService;

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
        log.info("Context built: {} classes analyzed   {} ms", response.totalClassesAnalyzed(), Duration.between(start, Instant.now()).toMillis());
        return ResponseEntity.ok(response);
    }
}
