package ru.kalinin.context.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Входящий запрос на построение контекста.
 *
 * @param gitlabUrl      URL GitLab-инстанса, например {@code https://gitlab.com}
 * @param projectId      ID проекта или {@code namespace/name}
 * @param token          Personal / Project Access Token
 * @param mergeRequestIid IID мёрж-реквеста (внутренний номер в проекте)
 * @param depth          желаемая глубина контекста (1 = только изменённые файлы)
 */
public record ContextRequest(

        @NotBlank(message = "gitlabUrl must not be blank")
        String gitlabUrl,

        @NotBlank(message = "projectId must not be blank")
        String projectId,

        @NotBlank(message = "token must not be blank")
        String token,

        @NotNull(message = "mergeRequestIid must not be null")
        Long mergeRequestIid,

        @Min(value = 1, message = "depth must be >= 1")
        int depth
) {}
