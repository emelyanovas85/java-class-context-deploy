package service.structure.model;

import java.time.Instant;

/**
 * Ответ {@code POST /api/review-sessions}: uid сессии и зафиксированные SHA.
 */
public record CreateSessionResponse(
        String sessionId,
        String sourceSha,
        String targetSha,
        String baseSha,
        Instant expiresAt
) {}
