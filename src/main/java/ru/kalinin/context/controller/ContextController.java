package ru.kalinin.context.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.kalinin.context.model.ContextRequest;
import ru.kalinin.context.model.ContextResponse;
import ru.kalinin.context.service.ContextBuilderService;

/**
 * REST-контроллер сервиса построения контекста.
 *
 * <p>POST /api/context — основной эндпоинт.
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ContextController {

    private final ContextBuilderService contextBuilderService;

    /**
     * Принимает параметры мёрж-реквеста и возвращает структуры всех затронутых классов
     * с заданной глубиной контекста.
     *
     * <pre>
     * POST /api/context
     * Content-Type: application/json
     *
     * {
     *   "gitlabUrl": "https://gitlab.com",
     *   "projectId": "mygroup/myproject",
     *   "token": "glpat-xxxx",
     *   "mergeRequestIid": 42,
     *   "depth": 2
     * }
     * </pre>
     */
    @PostMapping("/context")
    public ResponseEntity<ContextResponse> getContext(
            @Valid @RequestBody ContextRequest request) {

        log.info("Building context for MR !{} in project '{}', depth={}",
                request.mergeRequestIid(), request.projectId(), request.depth());

        ContextResponse response = contextBuilderService.buildContext(request);

        log.info("Context built: {} classes analyzed", response.totalClassesAnalyzed());
        return ResponseEntity.ok(response);
    }
}
