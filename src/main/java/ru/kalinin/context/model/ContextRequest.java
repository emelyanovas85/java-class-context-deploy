package ru.kalinin.context.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Входящий запрос на построение контекста.
 */
@Schema(description = "Параметры мёрж-реквеста GitLab для анализа")
public record ContextRequest(

        @Schema(description = "URL GitLab-инстанса", example = "https://gitlab.com")
        @NotBlank(message = "gitlabUrl must not be blank")
        String gitlabUrl,

        @Schema(description = "ID проекта или namespace/name", example = "mygroup/myproject")
        @NotBlank(message = "projectId must not be blank")
        String projectId,

        @Schema(description = "Personal или Project Access Token", example = "glpat-**MmA5L59_C_JYzqXpY23a**")
        @NotBlank(message = "token must not be blank")
        String token,

        @Schema(description = "IID мёрж-реквеста (внутренний номер в проекте)", example = "42")
        @NotNull(message = "mergeRequestIid must not be null")
        Long mergeRequestIid,

        @Schema(description = "Глубина контекста: 1 = только изменённые файлы, 2+ = + зависимости", example = "2", minimum = "1")
        @Min(value = 1, message = "depth must be >= 1")
        int depth
) {}
