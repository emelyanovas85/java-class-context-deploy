package service.structure.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Базовый запрос structure-эндпоинтов: сессия, глубина и опциональные корни обхода.
 */
@Schema(description = "Параметры построения структуры по сессии ревью")
public record SessionRequest(

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
        List<@NotBlank(message = "name must not be blank") String> names
) {}
