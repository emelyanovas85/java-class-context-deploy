package service.structure.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
@Tag(name = "Sources", description = """
        Получение исходного кода: полные файлы и фрагменты по номерам строк.

        Эндпоинты с сессией используют pinned `sourceSha` и merged index из create.
        `/api/source-lines/jar` — без сессии, по Maven-координатам локального sources.jar.

        **Когда что использовать:**
        - `/source-file` — полный текст файла по имени класса (repo + jar)
        - `/source-lines/gitlab` — конкретные строки из файлов MR (по `ClassContext.rows`)
        - `/source-lines/jar` — строки из внешней зависимости
        """)
public class SourceController {

    private final ReviewSessionResolver sessionResolver;
    private final SourceLinesService sourceLinesService;
    private final FileSourceService fileSourceService;

    @Operation(
            summary = "Строки из файлов GitLab (по сессии)",
            description = """
                    Возвращает запрошенные диапазоны строк из `.java`-файлов репозитория.

                    **Ref** = pinned `sourceSha` сессии (ветка MR). Credentials не нужны — только `sessionId`.

                    Для каждого класса укажите `qualifiedName`, опционально `source` (`main`/`test` для disambiguation)
                    и `rows` — диапазоны в формате `"28-168"` или `"17"`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Строки по каждому классу (или error в элементе)"),
            @ApiResponse(responseCode = "404", description = "Сессия не найдена"),
            @ApiResponse(responseCode = "410", description = "Сессия терминирована"),
            @ApiResponse(responseCode = "400", description = "Ошибка валидации")
    })
    @PostMapping("/source-lines/gitlab")
    public ResponseEntity<SourceLinesResponse> getGitLabLines(
            @Valid @RequestBody GitLabLinesSessionRequest request) {
        ReviewSession session = sessionResolver.requireActive(request.session().sessionId());
        log.info("Fetching GitLab source lines for session {}: classes={}",
                session.sessionId(), request.classes().size());
        return ResponseEntity.ok(sourceLinesService.fetchFromGitLab(session, request));
    }

    @Operation(
            summary = "Строки из sources.jar зависимости",
            description = """
                    Читает фрагменты из локального `*-sources.jar` по Maven-координатам.
                    **Сессия не требуется.**

                    `source` — `groupId:artifactId:version` (как поле `module` в `ClassContext` из Structure).
                    `classes` — qualified name + диапазоны строк.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Строки по каждому классу"),
            @ApiResponse(responseCode = "400", description = "Ошибка валидации или неверный формат module")
    })
    @PostMapping("/source-lines/jar")
    public ResponseEntity<SourceLinesResponse> getJarLines(@Valid @RequestBody JarLinesRequest request) {
        log.info("Fetching jar source lines: module='{}', classes={}",
                request.source(), request.classes().size());
        return ResponseEntity.ok(sourceLinesService.fetchFromJar(request));
    }

    @Operation(
            summary = "Полные исходники по именам",
            description = """
                    Возвращает **полный текст** `.java` для каждого имени из `names`.

                    Поиск: merged repo index + dependencySources (sources.jar). По каждому имени —
                    **все** совпадения (simple name может дать несколько файлов).

                    Побочный эффект: при первом вызове загружает dependencySources в сессию (если ещё не были).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Совпадения по каждому имени (может быть пустой files)"),
            @ApiResponse(responseCode = "404", description = "Сессия не найдена"),
            @ApiResponse(responseCode = "410", description = "Сессия терминирована"),
            @ApiResponse(responseCode = "400", description = "Ошибка валидации")
    })
    @PostMapping("/source-file")
    public ResponseEntity<FileSourceResponse> getSourceFile(@Valid @RequestBody FileSourceRequest request) {
        ReviewSession session = sessionResolver.requireActive(request.sessionId());
        log.info("Fetching source files for session {}: names={}",
                session.sessionId(), request.names());
        return ResponseEntity.ok(fileSourceService.resolve(session, request.names()));
    }
}
