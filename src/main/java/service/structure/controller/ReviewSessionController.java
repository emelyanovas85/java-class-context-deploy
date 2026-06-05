package service.structure.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import service.structure.model.CreateSessionRequest;
import service.structure.model.CreateSessionResponse;
import service.structure.model.SessionIdRequest;
import service.structure.session.ReviewSessionService;

@Slf4j
@RestController
@RequestMapping("/api/review-sessions")
@RequiredArgsConstructor
@Tag(name = "Sessions", description = """
        Жизненный цикл сессии ревью MR.

        **Сессия** — in-memory снимок: pin `sourceSha`/`targetSha`, diff, merged file index.
        GitLab token передаётся **только при create**; дальше достаточно `sessionId`.

        При обновлении MR: `DELETE` старой сессии → `POST` новой. Сервис не отслеживает изменения MR автоматически.
        """)
public class ReviewSessionController {

    private final ReviewSessionService reviewSessionService;

    @Operation(
            summary = "Создать сессию ревью",
            description = """
                    Первый шаг интеграции. Загружает MR из GitLab, фиксирует коммиты и строит merged file index.

                    **Возвращает сразу** после pin SHA и снимка MR: `sessionId`, `sourceSha`, `targetSha`, `baseSha`, `expiresAt`.
                    Построение merged file index идёт **в фоне** — не блокирует ответ.

                    Structure/Sources-запросы автоматически **ждут** готовности индекса (join).

                    **Повторный вызов** для того же MR терминирует предыдущую сессию.
                    `depth` и `names` задаются на Structure-эндпоинтах.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Сессия создана (индекс строится в фоне)"),
            @ApiResponse(responseCode = "422", description = "MR уже merged/closed — анализ невозможен"),
            @ApiResponse(responseCode = "503", description = "GitLab ещё не заполнил diff_refs для MR"),
            @ApiResponse(responseCode = "400", description = "Ошибка валидации тела запроса")
    })
    @PostMapping
    public ResponseEntity<CreateSessionResponse> create(@Valid @RequestBody CreateSessionRequest request) {
        log.info("Creating review session for MR !{} in project '{}'",
                request.mergeRequestIid(), request.projectId());
        CreateSessionResponse response = reviewSessionService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
            summary = "Терминировать сессию",
            description = """
                    Завершает работу с MR: отменяет in-flight построения контекста и удаляет данные из store.

                    **Идемпотентно:** всегда `204`, даже если сессия уже удалена или TTL истёк.

                    Вызывайте при обновлении MR, смене ревью или по таймауту оркестратора.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Сессия терминирована (или уже отсутствует)"),
            @ApiResponse(responseCode = "400", description = "Ошибка валидации")
    })
    @DeleteMapping
    public ResponseEntity<Void> terminate(@Valid @RequestBody SessionIdRequest request) {
        log.info("Terminating review session {}", request.sessionId());
        reviewSessionService.terminate(request.sessionId());
        return ResponseEntity.noContent().build();
    }
}
