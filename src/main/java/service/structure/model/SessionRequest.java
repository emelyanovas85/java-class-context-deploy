package service.structure.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Базовый запрос structure-эндпоинтов: сессия, глубина и опциональные корни обхода.
 */
@Schema(
        name = "SessionRequest",
        description = """
                Параметры построения структурного контекста.

                **depth:** `0` — только корневые файлы/классы; `N>0` — волновой обход зависимостей на N уровней.

                **names:** опционально. Корни BFS — simple/qualified имя или repo-путь `src/.../Foo.java`.
                Порядок резолва: repo-индекс → sources.jar. Без поля — все изменённые `.java` в MR.
                """,
        example = """
                {
                  "sessionId": "k7Fm2xQp",
                  "depth": 2,
                  "names": ["com.example.UserService", "src/main/java/com/example/Helper.java"]
                }
                """
)
public record SessionRequest(

        @Schema(description = "Uid сессии из POST /api/review-sessions", example = "k7Fm2xQp", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "sessionId must not be blank")
        String sessionId,

        @Schema(description = "Глубина BFS: 0 = только корни; 1+ = + зависимости repo и jar", example = "2", minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        @Min(value = 0, message = "depth must be >= 0")
        int depth,

        @Schema(description = """
                Корни обхода. null/отсутствует — все изменённые файлы MR.
                Пустой массив `[]` — ошибка 400.
                """, nullable = true, example = "[\"UserService\", \"org.springframework.stereotype.Service\"]")
        List<@NotBlank(message = "name must not be blank") String> names
) {}
