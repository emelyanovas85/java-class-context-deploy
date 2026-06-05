package service.structure.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import service.structure.exception.DiffRefsNotReadyException;
import service.structure.exception.MergeRequestAlreadyMergedException;
import service.structure.exception.ReviewSessionIndexBuildException;
import service.structure.exception.ReviewSessionNotFoundException;
import service.structure.exception.ReviewSessionTerminatedException;
import service.structure.exception.SeedFilesNotFoundException;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, message);
        pd.setTitle("Validation failed");
        return pd;
    }

    /** HTTP 404 — сессия не найдена или TTL. */
    @ExceptionHandler(ReviewSessionNotFoundException.class)
    public ProblemDetail handleSessionNotFound(ReviewSessionNotFoundException ex) {
        log.warn(ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setTitle("Review session not found");
        pd.setProperty("code", "SESSION_NOT_FOUND");
        return pd;
    }

    /** HTTP 503 — фоновое построение merged index не удалось. */
    @ExceptionHandler(ReviewSessionIndexBuildException.class)
    public ProblemDetail handleIndexBuildFailed(ReviewSessionIndexBuildException ex) {
        log.warn(ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        pd.setTitle("Session file index build failed");
        pd.setProperty("code", "SESSION_INDEX_BUILD_FAILED");
        return pd;
    }

    /** HTTP 410 — сессия терминирована. */
    @ExceptionHandler(ReviewSessionTerminatedException.class)
    public ProblemDetail handleSessionTerminated(ReviewSessionTerminatedException ex) {
        log.warn(ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.GONE, ex.getMessage());
        pd.setTitle("Review session terminated");
        pd.setProperty("code", "SESSION_TERMINATED");
        return pd;
    }

    @ExceptionHandler(SeedFilesNotFoundException.class)
    public ProblemDetail handleSeedFilesNotFound(SeedFilesNotFoundException ex) {
        log.warn(ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setTitle("Seed files not found");
        pd.setProperty("names", ex.names());
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        log.warn(ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setTitle("Bad request");
        return pd;
    }

    @ExceptionHandler(MergeRequestAlreadyMergedException.class)
    public ProblemDetail handleMerged(MergeRequestAlreadyMergedException ex) {
        log.warn(ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setTitle("Merge request already merged");
        return pd;
    }

    /** HTTP 503 — {@code diff_refs} ещё не готов у GitLab. */
    @ExceptionHandler(DiffRefsNotReadyException.class)
    public ProblemDetail handleDiffRefsNotReady(DiffRefsNotReadyException ex) {
        log.warn(ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        pd.setTitle("diff_refs not ready");
        return pd;
    }

    @ExceptionHandler(RuntimeException.class)
    public ProblemDetail handleRuntime(RuntimeException ex) {
        log.error("Runtime error", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
        pd.setTitle("Internal error");
        return pd;
    }
}
