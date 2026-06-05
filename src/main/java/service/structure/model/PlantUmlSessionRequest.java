package service.structure.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Запрос PlantUML: сессия, глубина, опциональные корни и форматирование.
 */
@Schema(
        name = "PlantUmlSessionRequest",
        description = "Как SessionRequest + опциональное форматирование PlantUML",
        example = """
                {
                  "sessionId": "k7Fm2xQp",
                  "depth": 2,
                  "names": ["com.example.OrderService"],
                  "pretty": true
                }
                """
)
public record PlantUmlSessionRequest(

        @Schema(description = "Uid сессии", example = "k7Fm2xQp", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "sessionId must not be blank")
        String sessionId,

        @Schema(description = "Глубина BFS (см. SessionRequest)", example = "2", minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        @Min(value = 0, message = "depth must be >= 0")
        int depth,

        @Schema(description = "Корни обхода (см. SessionRequest)", nullable = true)
        List<@NotBlank(message = "name must not be blank") String> names,

        @Schema(description = "true — читаемые отступы в PlantUML; false — компактный вывод. По умолчанию true", example = "true")
        Boolean pretty
) {
    /** {@code true} по умолчанию, если поле не передано. */
    public boolean prettyOrDefault() {
        return pretty == null || pretty;
    }
}
