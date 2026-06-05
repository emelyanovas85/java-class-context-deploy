package service.structure.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Запрос на создание сессии ревью: credentials и MR, без параметров построения контекста.
 */
@Schema(
        name = "CreateSessionRequest",
        description = """
                Параметры GitLab MR для создания сессии.
                После create все work-запросы используют только `sessionId` — token повторно не передаётся.
                """,
        example = """
                {
                  "gitlabUrl": "https://gitlab.com",
                  "projectId": "mygroup/myproject",
                  "token": "glpat-xxxxxxxxxxxx",
                  "mergeRequestIid": 42
                }
                """
)
public record CreateSessionRequest(

        @Schema(description = "URL GitLab-инстанса (без trailing slash)", example = "https://gitlab.com")
        @NotBlank(message = "gitlabUrl must not be blank")
        String gitlabUrl,

        @Schema(description = "ID проекта (число) или путь namespace/project", example = "mygroup/myproject")
        @NotBlank(message = "projectId must not be blank")
        String projectId,

        @Schema(description = "Personal Access Token или Project Access Token с правами read_api, read_repository",
                example = "glpat-xxxxxxxxxxxx")
        @NotBlank(message = "token must not be blank")
        String token,

        @Schema(description = "IID мёрж-реквеста — внутренний номер в проекте (!42 → 42)", example = "42")
        @NotNull(message = "mergeRequestIid must not be null")
        Long mergeRequestIid
) {}
