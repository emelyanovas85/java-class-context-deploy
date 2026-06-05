package service.structure.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Ссылка на сессию без параметров построения контекста.
 */
@Schema(
        description = "Только идентификатор сессии — для terminate и source-lines/gitlab",
        example = """
                { "sessionId": "k7Fm2xQp" }
                """
)
public record SessionIdRequest(

        @Schema(description = "Uid из ответа POST /api/review-sessions", example = "k7Fm2xQp")
        @NotBlank(message = "sessionId must not be blank")
        String sessionId
) {}
