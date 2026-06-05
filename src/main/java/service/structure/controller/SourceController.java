package service.structure.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import service.structure.model.FileSourceRequest;
import service.structure.model.FileSourceResponse;
import service.structure.model.GitLabLinesSessionRequest;
import service.structure.model.JarLinesRequest;
import service.structure.model.SourceLinesResponse;
import service.structure.service.FileSourceService;
import service.structure.service.SourceLinesService;
import service.structure.session.ReviewSession;
import service.structure.session.ReviewSessionResolver;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
/**
 * Получение исходного кода: строки по диапазонам, полный файл, jar (без сессии).
 */
@Tag(name = "Sources", description = "Получение исходного кода по сессии")
public class SourceController {

    private final ReviewSessionResolver sessionResolver;
    private final SourceLinesService sourceLinesService;
    private final FileSourceService fileSourceService;

    @Operation(summary = "Получить строки из файлов GitLab (по сессии)")
    @PostMapping("/source-lines/gitlab")
    public ResponseEntity<SourceLinesResponse> getGitLabLines(
            @Valid @RequestBody GitLabLinesSessionRequest request) {
        ReviewSession session = sessionResolver.requireActive(request.session().sessionId());
        log.info("Fetching GitLab source lines for session {}: classes={}",
                session.sessionId(), request.classes().size());
        return ResponseEntity.ok(sourceLinesService.fetchFromGitLab(session, request));
    }

    @Operation(summary = "Получить строки из sources.jar зависимости")
    @PostMapping("/source-lines/jar")
    public ResponseEntity<SourceLinesResponse> getJarLines(@Valid @RequestBody JarLinesRequest request) {
        log.info("Fetching jar source lines: module='{}', classes={}",
                request.source(), request.classes().size());
        return ResponseEntity.ok(sourceLinesService.fetchFromJar(request));
    }

    @Operation(summary = "Получить исходник файла по имени", description = """
            Имя simple или qualified. Поиск в merged index и dependencySources.
            Возвращает все совпадения.
            """)
    @PostMapping("/source-file")
    public ResponseEntity<FileSourceResponse> getSourceFile(@Valid @RequestBody FileSourceRequest request) {
        ReviewSession session = sessionResolver.requireActive(request.session().sessionId());
        log.info("Fetching source file for session {}: name='{}'",
                session.sessionId(), request.name());
        return ResponseEntity.ok(fileSourceService.resolve(session, request.name()));
    }
}
