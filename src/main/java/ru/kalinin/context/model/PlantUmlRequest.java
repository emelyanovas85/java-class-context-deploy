package ru.kalinin.context.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Запрос POST /api/plantuml и /api/plantuml/text.
 */
@Schema(description = "Параметры построения PlantUML по мёрж-реквесту GitLab")
public record PlantUmlRequest(

        @Schema(description = "Параметры MR (gitlabUrl, projectId, token, mergeRequestIid, depth)")
        @NotNull(message = "context must not be null")
        @Valid
        ContextRequest context,

        @Schema(
                description = "Форматирование PlantUML: true — с отступами и пустыми строками; false — компактно",
                defaultValue = "true"
        )
        Boolean pretty
) {
    /** По умолчанию {@code true}, если поле не передано. */
    public boolean prettyOrDefault() {
        return pretty == null || pretty;
    }
}
