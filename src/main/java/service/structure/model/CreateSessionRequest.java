package service.structure.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Запрос на создание сессии ревью: credentials и MR, без параметров построения контекста.
 */
@Schema(description = "Параметры мёрж-реквеста GitLab для создания сессии")
public record CreateSessionRequest(

        @Schema(description = "URL GitLab-инстанса", example = "https://gitlab.com")
        @NotBlank(message = "gitlabUrl must not be blank")
        String gitlabUrl,

        @Schema(description = "ID проекта или namespace/name", example = "mygroup/myproject")
        @NotBlank(message = "projectId must not be blank")
        String projectId,

        @Schema(description = "Personal или Project Access Token", example = "glpat-xxxxxxxxxxxx")
        @NotBlank(message = "token must not be blank")
        String token,

        @Schema(description = "IID мёрж-реквеста (внутренний номер в проекте)", example = "42")
        @NotNull(message = "mergeRequestIid must not be null")
        Long mergeRequestIid
) {}
