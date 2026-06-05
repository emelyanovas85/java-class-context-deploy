package service.structure.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Базовый запрос work-эндпоинтов: только {@code sessionId}.
 */
@Schema(description = "Идентификатор сессии ревью")
public record SessionRequest(

        @Schema(description = "Короткий uid сессии", example = "k7Fm2xQp")
        @NotBlank(message = "sessionId must not be blank")
        String sessionId
) {}
