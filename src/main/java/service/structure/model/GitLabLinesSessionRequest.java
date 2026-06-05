package service.structure.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Запрос строк GitLab: credentials и ref берутся из сессии (pinned {@code sourceSha}).
 */
@Schema(description = "Запрос строк из GitLab по сессии")
public record GitLabLinesSessionRequest(

        @NotNull(message = "session must not be null")
        @Valid
        SessionIdRequest session,

        @NotEmpty(message = "classes must not be empty")
        @Valid
        List<ClassLines> classes
) {}
