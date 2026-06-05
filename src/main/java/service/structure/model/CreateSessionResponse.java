package service.structure.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Ответ {@code POST /api/review-sessions}: uid сессии и зафиксированные SHA.
 */
@Schema(description = "Созданная сессия ревью с pin коммитов")
public record CreateSessionResponse(

        @Schema(description = "Короткий uid сессии — передавать во все последующие запросы", example = "k7Fm2xQp")
        String sessionId,

        @Schema(description = "SHA HEAD source-ветки MR (версия с изменениями)", example = "e82eb4a0c1d2…")
        String sourceSha,

        @Schema(description = "SHA target-ветки MR", example = "1162f719ab3c…")
        String targetSha,

        @Schema(description = "SHA merge-base для diff", example = "1162f719ab3c…")
        String baseSha,

        @Schema(description = "Момент истечения сессии (expireAfterWrite)", example = "2026-06-05T14:00:00Z")
        Instant expiresAt
) {}
