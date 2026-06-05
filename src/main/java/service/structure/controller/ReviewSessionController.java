package service.structure.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import service.structure.model.ContextRequest;
import service.structure.model.CreateSessionResponse;
import service.structure.model.SessionRequest;
import service.structure.session.ReviewSessionService;

@Slf4j
@RestController
@RequestMapping("/api/review-sessions")
@RequiredArgsConstructor
/**
 * REST API жизненного цикла сессии: create (pin SHA) и terminate.
 */
@Tag(name = "Sessions", description = "Создание и терминация сессий ревью MR")
public class ReviewSessionController {

    private final ReviewSessionService reviewSessionService;

    @Operation(summary = "Создать сессию ревью", description = """
            Фиксирует SHA source/target и снимок diff MR.
            Повторный create на тот же MR терминирует предыдущую сессию.
            """)
    @PostMapping
    public ResponseEntity<CreateSessionResponse> create(@Valid @RequestBody ContextRequest request) {
        log.info("Creating review session for MR !{} in project '{}', depth={}",
                request.mergeRequestIid(), request.projectId(), request.depth());
        CreateSessionResponse response = reviewSessionService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Терминировать сессию", description = """
            Немедленно отменяет in-flight задачи и удаляет сессию из store.
            Идемпотентно: 204 даже если сессия уже удалена.
            """)
    @DeleteMapping
    public ResponseEntity<Void> terminate(@Valid @RequestBody SessionRequest request) {
        log.info("Terminating review session {}", request.sessionId());
        reviewSessionService.terminate(request.sessionId());
        return ResponseEntity.noContent().build();
    }
}
