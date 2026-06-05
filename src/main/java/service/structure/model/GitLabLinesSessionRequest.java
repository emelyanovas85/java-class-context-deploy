package service.structure.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Запрос строк GitLab: credentials и ref берутся из сессии (pinned {@code sourceSha}).
 */
@Schema(
        description = "Фрагменты исходников из репозитория MR по pinned sourceSha",
        example = """
                {
                  "session": { "sessionId": "k7Fm2xQp" },
                  "classes": [
                    {
                      "qualifiedName": "com.example.Foo",
                      "source": "main",
                      "rows": ["28-168"]
                    }
                  ]
                }
                """
)
public record GitLabLinesSessionRequest(

        @Schema(description = "Ссылка на сессию (только sessionId)", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "session must not be null")
        @Valid
        SessionIdRequest session,

        @Schema(description = "Список классов и диапазонов строк", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty(message = "classes must not be empty")
        @Valid
        List<ClassLines> classes
) {}
