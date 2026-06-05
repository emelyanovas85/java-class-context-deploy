package service.structure.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Запрос PlantUML: сессия, глубина, опциональные корни и форматирование.
 */
@Schema(description = "Параметры построения PlantUML по сессии ревью")
public record PlantUmlSessionRequest(

        @Schema(description = "Короткий uid сессии", example = "k7Fm2xQp")
        @NotBlank(message = "sessionId must not be blank")
        String sessionId,

        @Schema(description = "Глубина контекста: 0 = только корневые файлы, 1+ = + зависимости", example = "2", minimum = "0")
        @Min(value = 0, message = "depth must be >= 0")
        int depth,

        @Schema(description = """
                Корни обхода: simple/qualified имя или repo-путь .java.
                Сначала ищется в repo-индексе, затем в dependencySources (sources.jar).
                Не указано — все изменённые файлы MR.
                """)
        List<@NotBlank(message = "name must not be blank") String> names,

        @Schema(description = "Форматирование PlantUML", defaultValue = "true")
        Boolean pretty
) {
    /** {@code true} по умолчанию, если поле не передано. */
    public boolean prettyOrDefault() {
        return pretty == null || pretty;
    }
}
