package service.structure.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Запрос PlantUML: сессия + опциональный флаг форматирования.
 */
@Schema(description = "Параметры построения PlantUML по сессии ревью")
public record PlantUmlSessionRequest(

        @NotNull(message = "session must not be null")
        @Valid
        SessionRequest session,

        @Schema(description = "Форматирование PlantUML", defaultValue = "true")
        Boolean pretty
) {
    /** {@code true} по умолчанию, если поле не передано. */
    public boolean prettyOrDefault() {
        return pretty == null || pretty;
    }
}
