package service.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * DTO-обёртки для общения с основным сервисом Java Class Context API (порт 8084).
 *
 * <p>Структуры повторяют тела запросов/ответов REST API основного сервиса.
 * Для тел запросов поля помечены {@link JsonInclude}, чтобы {@code null} (например, не заданный
 * {@code names}) не попадал в JSON — это соответствует семантике API
 * («без поля names — все изменённые .java в MR»).
 */
public final class SessionDtos {

    private SessionDtos() {
    }

    // ---------------------------------------------------------------------
    // Sessions
    // ---------------------------------------------------------------------

    /** Тело POST /api/review-sessions. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreateSessionRequest(
            String gitlabUrl,
            String projectId,
            String token,
            Long mergeRequestIid
    ) {
    }

    /** Ответ POST /api/review-sessions. */
    public record CreateSessionResponse(
            String sessionId,
            String sourceSha,
            String targetSha,
            String baseSha,
            String expiresAt
    ) {
    }

    /** Тело DELETE /api/review-sessions и /api/source-lines/gitlab.session. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SessionIdRequest(
            String sessionId
    ) {
    }

    // ---------------------------------------------------------------------
    // Structure
    // ---------------------------------------------------------------------

    /** Тело /api/structure/{json,html,markdown}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SessionRequest(
            String sessionId,
            int depth,
            List<String> names
    ) {
    }

    /** Тело /api/structure/plantuml/{object,text}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlantUmlSessionRequest(
            String sessionId,
            int depth,
            List<String> names,
            Boolean pretty
    ) {
    }

    // ---------------------------------------------------------------------
    // Sources
    // ---------------------------------------------------------------------

    /** Тело /api/source-file. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FileSourceRequest(
            String sessionId,
            List<String> names
    ) {
    }

    /** Один класс с набором диапазонов строк (общий для gitlab/jar запросов). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ClassLines(
            String qualifiedName,
            String source,
            List<String> rows
    ) {
    }

    /** Тело /api/source-lines/gitlab. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GitLabLinesSessionRequest(
            SessionIdRequest session,
            List<ClassLines> classes
    ) {
    }

    /** Тело /api/source-lines/jar. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JarLinesRequest(
            String source,
            List<ClassLines> classes
    ) {
    }
}
